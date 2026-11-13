.PHONY: build test coverage up down run migrate smoke

MVN := ./mvnw

build:
	$(MVN) -DskipTests package

test:
	$(MVN) verify

coverage: test
	@python3 -c 'import xml.etree.ElementTree as ET; c=ET.parse("target/site/jacoco/jacoco.xml").getroot().findall("counter"); line=next(x for x in c if x.get("type")=="LINE"); m,k=int(line.get("missed")),int(line.get("covered")); print(f"Jacoco LINE coverage={100*k/(m+k):.2f}% ({k}/{m+k})")'
	@echo "Report: target/site/jacoco/index.html"

up:
	docker compose up -d postgres

down:
	docker compose down -v

run:
	$(MVN) spring-boot:run

migrate: run

smoke:
	curl -sf http://localhost:8088/actuator/health | grep -q '"status":"UP"'
