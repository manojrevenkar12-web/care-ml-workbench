variable "region" {
  description = "eu-central-1 for EU tenants, us-east-1 for US tenants with a signed BAA. Data never crosses regions."
  type        = string
  validation {
    condition     = contains(["eu-central-1", "us-east-1"], var.region)
    error_message = "Only eu-central-1 and us-east-1 are approved for PHI."
  }
}

variable "environment" {
  type    = string
  default = "production"
}

variable "vpc_cidr" {
  type    = string
  default = "10.0.0.0/16"
}

variable "kubernetes_version" {
  type    = string
  default = "1.31"
}

variable "db_instance_class" {
  type    = string
  default = "db.r6g.large"
}
