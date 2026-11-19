variable "name" {
  description = "Name prefix for all network resources."
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR block of the VPC."
  type        = string
  default     = "10.20.0.0/16"
}

variable "tags" {
  description = "Tags applied to every resource."
  type        = map(string)
  default     = {}
}
