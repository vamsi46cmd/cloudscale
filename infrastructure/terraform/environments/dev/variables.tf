variable "region" {
  description = "AWS region."
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  type    = string
  default = "dev"
}

variable "eks_public_access_cidrs" {
  description = "CIDRs allowed to reach the public Kubernetes API (for example your office /32). Empty keeps the endpoint private."
  type        = list(string)
  default     = []
}
