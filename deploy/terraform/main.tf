locals {
  name = "care-ml-workbench-${var.region == "us-east-1" ? "us" : "eu"}"
  azs  = slice(data.aws_availability_zones.available.names, 0, 3)
}

data "aws_availability_zones" "available" {
  state = "available"
}

data "aws_caller_identity" "current" {}

# ------------------------------------------------------------------ encryption
# One customer-managed key per region encrypts everything that can hold PHI: the database, the
# shared volume, the inbox bucket, Kubernetes secrets, and the application's envelope keys.
resource "aws_kms_key" "phi" {
  description             = "${local.name} PHI encryption"
  enable_key_rotation     = true
  deletion_window_in_days = 30
}

resource "aws_kms_alias" "phi" {
  name          = "alias/${local.name}-phi"
  target_key_id = aws_kms_key.phi.key_id
}

# ------------------------------------------------------------------ network
module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 5.16"

  name                 = local.name
  cidr                 = var.vpc_cidr
  azs                  = local.azs
  private_subnets      = [for i, _ in local.azs : cidrsubnet(var.vpc_cidr, 4, i)]
  public_subnets       = [for i, _ in local.azs : cidrsubnet(var.vpc_cidr, 8, 48 + i)]
  database_subnets     = [for i, _ in local.azs : cidrsubnet(var.vpc_cidr, 8, 64 + i)]
  enable_nat_gateway   = true
  single_nat_gateway   = false
  enable_dns_hostnames = true

  private_subnet_tags = { "kubernetes.io/role/internal-elb" = 1 }
  public_subnet_tags  = { "kubernetes.io/role/elb" = 1 }
}

# ------------------------------------------------------------------ cluster
module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 20.31"

  cluster_name                             = local.name
  cluster_version                          = var.kubernetes_version
  vpc_id                                   = module.vpc.vpc_id
  subnet_ids                               = module.vpc.private_subnets
  cluster_endpoint_public_access           = false
  enable_irsa                              = true
  enable_cluster_creator_admin_permissions = true

  create_kms_key = false
  cluster_encryption_config = {
    provider_key_arn = aws_kms_key.phi.arn
    resources        = ["secrets"]
  }

  cluster_addons = {
    coredns    = {}
    kube-proxy = {}
    vpc-cni = {
      # enforce Kubernetes NetworkPolicies (deploy/k8s/base/networkpolicies.yaml)
      configuration_values = jsonencode({ enableNetworkPolicy = "true" })
    }
    aws-efs-csi-driver = {
      service_account_role_arn = module.efs_csi_irsa.iam_role_arn
    }
  }

  eks_managed_node_groups = {
    general = {
      instance_types = ["m6i.xlarge"]
      min_size       = 2
      max_size       = 6
      desired_size   = 3
      block_device_mappings = {
        root = {
          device_name = "/dev/xvda"
          ebs         = { volume_size = 50, encrypted = true, kms_key_id = aws_kms_key.phi.arn }
        }
      }
    }
  }
}

module "efs_csi_irsa" {
  source  = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  version = "~> 5.48"

  role_name             = "${local.name}-efs-csi"
  attach_efs_csi_policy = true
  oidc_providers = {
    main = {
      provider_arn               = module.eks.oidc_provider_arn
      namespace_service_accounts = ["kube-system:efs-csi-controller-sa"]
    }
  }
}

# ------------------------------------------------------------------ shared volume (de-identified datasets, model artifacts)
resource "aws_efs_file_system" "artifacts" {
  creation_token = "${local.name}-artifacts"
  encrypted      = true
  kms_key_id     = aws_kms_key.phi.arn
}

resource "aws_security_group" "efs" {
  name_prefix = "${local.name}-efs-"
  vpc_id      = module.vpc.vpc_id
  ingress {
    from_port       = 2049
    to_port         = 2049
    protocol        = "tcp"
    security_groups = [module.eks.node_security_group_id]
  }
}

resource "aws_efs_mount_target" "artifacts" {
  count           = length(local.azs)
  file_system_id  = aws_efs_file_system.artifacts.id
  subnet_id       = module.vpc.private_subnets[count.index]
  security_groups = [aws_security_group.efs.id]
}

# ------------------------------------------------------------------ database (items, sealed PHI, annotations, audit)
resource "aws_db_subnet_group" "main" {
  name       = local.name
  subnet_ids = module.vpc.database_subnets
}

resource "aws_security_group" "db" {
  name_prefix = "${local.name}-db-"
  vpc_id      = module.vpc.vpc_id
  ingress {
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [module.eks.node_security_group_id]
  }
}

resource "aws_db_parameter_group" "main" {
  name   = local.name
  family = "postgres16"
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
  parameter {
    name  = "log_statement"
    value = "ddl"
  }
}

resource "aws_db_instance" "main" {
  identifier                          = local.name
  engine                              = "postgres"
  engine_version                      = "16"
  instance_class                      = var.db_instance_class
  allocated_storage                   = 100
  max_allocated_storage               = 500
  db_name                             = "workbench"
  username                            = "workbench"
  manage_master_user_password         = true # rotated in Secrets Manager, never in Terraform state
  master_user_secret_kms_key_id       = aws_kms_key.phi.arn
  storage_encrypted                   = true
  kms_key_id                          = aws_kms_key.phi.arn
  multi_az                            = true
  db_subnet_group_name                = aws_db_subnet_group.main.name
  vpc_security_group_ids              = [aws_security_group.db.id]
  parameter_group_name                = aws_db_parameter_group.main.name
  backup_retention_period             = 14
  deletion_protection                 = true
  skip_final_snapshot                 = false
  final_snapshot_identifier           = "${local.name}-final"
  iam_database_authentication_enabled = true
  performance_insights_enabled        = true
  performance_insights_kms_key_id     = aws_kms_key.phi.arn
  auto_minor_version_upgrade          = true
  enabled_cloudwatch_logs_exports     = ["postgresql"]
}

# ------------------------------------------------------------------ inbox for customer exports
resource "aws_s3_bucket" "inbox" {
  bucket_prefix = "${local.name}-inbox-"
}

resource "aws_s3_bucket_public_access_block" "inbox" {
  bucket                  = aws_s3_bucket.inbox.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "inbox" {
  bucket = aws_s3_bucket.inbox.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.phi.arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_versioning" "inbox" {
  bucket = aws_s3_bucket.inbox.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "inbox" {
  bucket = aws_s3_bucket.inbox.id
  rule {
    id     = "expire-raw-exports"
    status = "Enabled"
    filter {}
    expiration {
      days = 30 # raw exports are deleted once ingested and sealed
    }
    noncurrent_version_expiration {
      noncurrent_days = 7
    }
  }
}

data "aws_iam_policy_document" "inbox_tls_only" {
  statement {
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.inbox.arn, "${aws_s3_bucket.inbox.arn}/*"]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "inbox" {
  bucket = aws_s3_bucket.inbox.id
  policy = data.aws_iam_policy_document.inbox_tls_only.json
}

# ------------------------------------------------------------------ application secrets (values set out of band)
resource "aws_secretsmanager_secret" "app" {
  name       = "${local.name}/app"
  kms_key_id = aws_kms_key.phi.arn
  # WORKBENCH_MASTER_KEY and WORKBENCH_DEID_KEY_<TENANT>; synced into the cluster by External Secrets
}

# ------------------------------------------------------------------ workload identities (IRSA)
data "aws_iam_policy_document" "api" {
  statement {
    actions   = ["kms:Decrypt", "kms:Encrypt", "kms:GenerateDataKey"]
    resources = [aws_kms_key.phi.arn]
  }
  statement {
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_secretsmanager_secret.app.arn, aws_db_instance.main.master_user_secret[0].secret_arn]
  }
}

resource "aws_iam_policy" "api" {
  name   = "${local.name}-api"
  policy = data.aws_iam_policy_document.api.json
}

data "aws_iam_policy_document" "etl" {
  source_policy_documents = [data.aws_iam_policy_document.api.json]
  statement {
    actions   = ["s3:GetObject", "s3:ListBucket", "s3:DeleteObject"]
    resources = [aws_s3_bucket.inbox.arn, "${aws_s3_bucket.inbox.arn}/*"]
  }
}

resource "aws_iam_policy" "etl" {
  name   = "${local.name}-etl"
  policy = data.aws_iam_policy_document.etl.json
}

module "api_irsa" {
  source  = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  version = "~> 5.48"

  role_name        = "${local.name}-api"
  role_policy_arns = { app = aws_iam_policy.api.arn }
  oidc_providers = {
    main = {
      provider_arn               = module.eks.oidc_provider_arn
      namespace_service_accounts = ["ml-app:api"]
    }
  }
}

module "etl_irsa" {
  source  = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  version = "~> 5.48"

  role_name        = "${local.name}-etl"
  role_policy_arns = { app = aws_iam_policy.etl.arn }
  oidc_providers = {
    main = {
      provider_arn               = module.eks.oidc_provider_arn
      namespace_service_accounts = ["ml-app:etl"]
    }
  }
}
