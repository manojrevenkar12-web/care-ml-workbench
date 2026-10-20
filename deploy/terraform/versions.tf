terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.80" }
  }
  # one state per region: terraform init -backend-config=backend-eu.hcl
  backend "s3" {}
}

provider "aws" {
  region = var.region
  default_tags {
    tags = {
      project     = "care-ml-workbench"
      environment = var.environment
      data-class  = "phi"
    }
  }
}
