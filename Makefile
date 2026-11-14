.PHONY: build test coverage up db-up down run migrate smoke e2e-harness

MVN := ./mvnw

# Quick smoke defaults for live harness e2e (override via env).
E2E_MAX_MESSAGES ?= 60
E2E_MESSAGE_DELAY ?= 100ms
E2E_CONCURRENCY ?= 1

build:
	$(MVN) -DskipTests package

test:
	$(MVN) verify

coverage: test
	@python3 -c 'import xml.etree.ElementTree as ET; c=ET.parse("target/site/jacoco/jacoco.xml").getroot().findall("counter"); line=next(x for x in c if x.get("type")=="LINE"); m,k=int(line.get("missed")),int(line.get("covered")); print(f"Jacoco LINE coverage={100*k/(m+k):.2f}% ({k}/{m+k})")'
	@echo "Report: target/site/jacoco/index.html"

up:
	docker compose up -d --build

db-up:
	docker compose up -d postgres

down:
	docker compose down -v

run:
	$(MVN) spring-boot:run

migrate: run

smoke:
	curl -sf http://localhost:8088/actuator/health | grep -q '"status":"UP"'

# Live vendor harness → capture ALL channel IDs → poll apply → assert GET list+detail.
e2e-harness:
	@python3 scripts/e2e_harness.py --up \
		--max-messages=$(E2E_MAX_MESSAGES) \
		--message-delay=$(E2E_MESSAGE_DELAY) \
		--concurrency-level=$(E2E_CONCURRENCY)
