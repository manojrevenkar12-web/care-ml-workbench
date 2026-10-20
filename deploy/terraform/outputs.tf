output "cluster_name" {
  value = module.eks.cluster_name
}

output "efs_file_system_id" {
  description = "Set as fileSystemId in deploy/k8s/cluster/storageclass.yaml"
  value       = aws_efs_file_system.artifacts.id
}

output "database_endpoint" {
  value = aws_db_instance.main.endpoint
}

output "api_role_arn" {
  value = module.api_irsa.iam_role_arn
}

output "etl_role_arn" {
  value = module.etl_irsa.iam_role_arn
}

output "inbox_bucket" {
  value = aws_s3_bucket.inbox.bucket
}
