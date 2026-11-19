variable "name" {
  type = string
}

variable "kubernetes_version" {
  type    = string
  default = "1.31"
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "public_access_cidrs" {
  description = "CIDRs allowed to reach the public Kubernetes API endpoint. Empty = private endpoint only."
  type        = list(string)
  default     = []
}

variable "node_instance_types" {
  type    = list(string)
  default = ["t3.medium"]
}

variable "node_desired_size" {
  type    = number
  default = 2
}

variable "node_min_size" {
  type    = number
  default = 2
}

variable "node_max_size" {
  type    = number
  default = 4
}

variable "tags" {
  type    = map(string)
  default = {}
}
