IG_PUBLISHER = ./.bin/org.hl7.fhir.publisher.jar
REPORT_COVERAGE ?= false

SMOKE_THREADS ?= 10

${IG_PUBLISHER}:
	-mkdir ./.bin
	curl https://fhir.github.io/latest-ig-publisher/org.hl7.fhir.publisher.jar -o ${IG_PUBLISHER}

venv: venv/bin/activate

venv/bin/activate: requirements.txt
	test -d venv || virtualenv venv
	. venv/bin/activate; pip install -Ur requirements.txt
	touch venv/bin/activate



.PHONY: ig/publish
ig/publish: ${IG_PUBLISHER}
	@echo "Building Implementation Guide"
	@java -jar ${IG_PUBLISHER} -ig ig/ig.json

.PHONY: travis
travis:
	@./dpc-test.sh

.PHONY: website
website:
	@docker build -f dpc-web/Dockerfile . -t dpc-web

.PHONY: start-app
start-app:
	@docker-compose up start_core_dependencies
	@docker-compose up start_api_dependencies
	@docker-compose up start_api

.PHONY: ci-app
ci-app: docker-base
	@./dpc-test.sh

.PHONY: ci-web
ci-web:
	@./dpc-web-test.sh

.PHONY: smoke/local
smoke/local: venv
	@echo "Running Smoke Tests against Local env"
	. venv/bin/activate; bzt src/test/local.smoke_test.yml

.PHONY: smoke/dev
smoke/dev: venv
	@echo "Running Smoke Tests against Development env"
	. venv/bin/activate; bzt src/test/dev.smoke_test.yml

.PHONY: smoke/test
smoke/test: venv
	. venv/bin/activate; bzt src/test/test.smoke_test.yml

.PHONY: smoke/prod-sbx
smoke/prod-sbx: venv
	@echo "Running Smoke Tests against Sandbox env"
	. venv/bin/activate; bzt src/test/prod-sbx.smoke_test.yml

.PHONY: docker-base
docker-base:
	@docker-compose -f ./docker-compose.base.yml build base
