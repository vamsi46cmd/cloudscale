output "ecr_repository_url" {
  value = module.ecr.repository_url
}

output "eks_cluster_name" {
  value = module.eks.cluster_name
}

output "database_endpoint" {
  value = module.database.endpoint
}

output "database_secret_arn" {
  description = "Secrets Manager ARN of the generated database password."
  value       = module.database.master_secret_arn
}

output "redis_endpoint" {
  value = module.redis.primary_endpoint
}
