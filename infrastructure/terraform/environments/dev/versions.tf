terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.80"
    }
  }

  # Remote state: state files contain resource attributes (some sensitive), so never commit them and never keep them
  # only on a laptop. Enable an encrypted S3 backend with locking, created once outside this configuration:
  #
  # backend "s3" {
  #   bucket         = "<your-org>-terraform-state"
  #   key            = "cloudscale/dev/terraform.tfstate"
  #   region         = "us-east-1"
  #   encrypt        = true
  #   kms_key_id     = "alias/terraform-state"
  #   dynamodb_table = "terraform-locks"
  # }
}
