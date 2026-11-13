.PHONY: build test up down run migrate smoke

MVN := ./mvnw

build:
	$(MVN) -DskipTests package

test:
	$(MVN) verify

up:
	docker compose up -d postgres

down:
	docker compose down -v

run:
	$(MVN) spring-boot:run

migrate: run

smoke:
	curl -sf http://localhost:8088/actuator/health | grep -q '"status":"UP"'
