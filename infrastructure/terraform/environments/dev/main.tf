provider "aws" {
  region = var.region

  default_tags {
    tags = local.tags
  }
}

locals {
  name = "cloudscale-${var.environment}"
  tags = {
    Project     = "cloudscale"
    Environment = var.environment
    ManagedBy   = "terraform"
  }
}

module "network" {
  source = "../../modules/network"
  name   = local.name
  tags   = local.tags
}

module "ecr" {
  source       = "../../modules/ecr"
  name         = local.name
  force_delete = true # dev only
  tags         = local.tags
}

module "eks" {
  source              = "../../modules/eks"
  name                = local.name
  private_subnet_ids  = module.network.private_subnet_ids
  public_access_cidrs = var.eks_public_access_cidrs
  tags                = local.tags
}

module "database" {
  source                    = "../../modules/database"
  name                      = local.name
  vpc_id                    = module.network.vpc_id
  private_subnet_ids        = module.network.private_subnet_ids
  allowed_security_group_id = module.eks.cluster_security_group_id
  tags                      = local.tags
}

module "redis" {
  source                    = "../../modules/redis"
  name                      = local.name
  vpc_id                    = module.network.vpc_id
  private_subnet_ids        = module.network.private_subnet_ids
  allowed_security_group_id = module.eks.cluster_security_group_id
  tags                      = local.tags
}
