# RDS PostgreSQL in private subnets. Reachable only from the EKS security group. The master password is generated and
# stored by AWS Secrets Manager (manage_master_user_password), so it never appears in Terraform variables or code.
resource "aws_db_subnet_group" "this" {
  name       = "${var.name}-db"
  subnet_ids = var.private_subnet_ids
  tags       = var.tags
}

resource "aws_security_group" "db" {
  name        = "${var.name}-db"
  description = "PostgreSQL access from the application only"
  vpc_id      = var.vpc_id
  tags        = var.tags
}

resource "aws_vpc_security_group_ingress_rule" "from_app" {
  security_group_id            = aws_security_group.db.id
  referenced_security_group_id = var.allowed_security_group_id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
  description                  = "PostgreSQL from EKS"
}

resource "aws_db_instance" "this" {
  identifier     = "${var.name}-postgres"
  engine         = "postgres"
  engine_version = var.engine_version
  instance_class = var.instance_class

  db_name  = "cloudscale"
  username = "cloudscale"

  manage_master_user_password = true

  allocated_storage     = var.allocated_storage_gb
  max_allocated_storage = var.allocated_storage_gb * 4
  storage_type          = "gp3"
  storage_encrypted     = true

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.db.id]
  publicly_accessible    = false
  multi_az               = var.multi_az

  backup_retention_period             = var.backup_retention_days
  copy_tags_to_snapshot               = true
  deletion_protection                 = var.deletion_protection
  skip_final_snapshot                 = !var.deletion_protection
  final_snapshot_identifier           = var.deletion_protection ? "${var.name}-final" : null
  auto_minor_version_upgrade          = true
  performance_insights_enabled        = true
  iam_database_authentication_enabled = true

  tags = var.tags
}
