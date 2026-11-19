# AWS infrastructure (Terraform): validated, never applied

`infrastructure/terraform` defines the optional AWS deployment. **`terraform apply` was never run and no AWS resources
were created.** What was verified (Terraform 1.17, AWS provider 5.x, lock file committed):

```bash
cd infrastructure/terraform
terraform fmt -check -recursive          # passes
cd environments/dev
terraform init -backend=false            # passes
terraform validate                       # "The configuration is valid."
```

This proves the configuration is syntactically and structurally valid. It does **not** prove it deploys: IAM permissions,
quotas, regional availability, and AWS-side validation of argument combinations are only checked by a real `plan`/`apply`.

## Layout

| Module | Creates |
|---|---|
| `network` | VPC, 2 public + 2 private subnets, internet gateway, one NAT gateway, route tables |
| `ecr` | Image repository: immutable tags, scan on push, keeps the 20 newest images |
| `eks` | EKS cluster (private subnets, KMS-encrypted secrets, audit logs to CloudWatch), managed node group, node and cluster IAM roles |
| `database` | RDS PostgreSQL: private subnets, encrypted at rest, not publicly accessible, password generated and held in AWS Secrets Manager |
| `redis` | ElastiCache Redis: private subnets, encrypted at rest and in transit (TLS) |

Security properties by construction: databases accept connections **only from the EKS cluster security group**; nodes run in
private subnets; the Kubernetes API is private unless CIDRs are listed in `eks_public_access_cidrs`; node IAM uses only the
three AWS-managed worker policies; no credentials appear in code or variables (the database master password is managed by AWS).
Redis transit encryption means clients must use TLS: set `REDIS_SSL=true` (Helm: `config.REDIS_SSL`). That switch exists in the
application but has **not** been tested against a real ElastiCache cluster.

## State

Never commit state (`.gitignore` excludes `*.tfstate`, `.terraform/`, `*.tfvars`). State contains resource attributes, so use
an encrypted remote backend with locking; a commented S3 + KMS + DynamoDB example is in `environments/dev/versions.tf`
(the bucket and table must be created once, outside this configuration).

## Cost (approximate, us-east-1 list prices; confirm with the AWS Pricing Calculator)

If left running, the dev environment costs roughly **US$180-220 per month**: EKS control plane about $73, two t3.medium
nodes about $60, NAT gateway about $32 plus data processing, RDS db.t4g.micro about $12-15, ElastiCache cache.t4g.micro
about $12, plus CloudWatch logs and storage. Destroy it when not in use. The EKS control plane and NAT gateway are the
fixed costs that do not scale to zero.
