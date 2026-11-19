-- Created on first start of an empty Postgres volume. Integration tests (mvn verify) use this separate database so the
-- docker-compose workers never claim jobs created by tests. Flyway creates the schema when the tests start.
CREATE DATABASE cloudscale_test OWNER cloudscale;
