# ElastiCache Redis in private subnets, encrypted at rest and in transit, reachable only from the EKS security group.
resource "aws_elasticache_subnet_group" "this" {
  name       = "${var.name}-redis"
  subnet_ids = var.private_subnet_ids
  tags       = var.tags
}

resource "aws_security_group" "redis" {
  name        = "${var.name}-redis"
  description = "Redis access from the application only"
  vpc_id      = var.vpc_id
  tags        = var.tags
}

resource "aws_vpc_security_group_ingress_rule" "from_app" {
  security_group_id            = aws_security_group.redis.id
  referenced_security_group_id = var.allowed_security_group_id
  from_port                    = 6379
  to_port                      = 6379
  ip_protocol                  = "tcp"
  description                  = "Redis from EKS"
}

resource "aws_elasticache_replication_group" "this" {
  replication_group_id = "${var.name}-redis"
  description          = "CloudScale cache and rate limiter"
  engine               = "redis"
  engine_version       = var.engine_version
  node_type            = var.node_type
  port                 = 6379

  num_cache_clusters         = var.multi_az ? 2 : 1
  automatic_failover_enabled = var.multi_az
  multi_az_enabled           = var.multi_az

  subnet_group_name  = aws_elasticache_subnet_group.this.name
  security_group_ids = [aws_security_group.redis.id]

  at_rest_encryption_enabled = true
  transit_encryption_enabled = true # clients must use TLS (rediss://)

  snapshot_retention_limit = 0 # cache and rate-limit state only; nothing here is a system of record
  tags                     = var.tags
}
