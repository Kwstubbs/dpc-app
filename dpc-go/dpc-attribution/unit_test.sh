#!/bin/bash
#
# Run all ATTRIBUTION unit tests and coverage
#
timestamp=`date +%Y-%m-%d_%H-%M-%S`
mkdir -p test_results/${timestamp}
mkdir -p test_results/latest

echo "Running GO DPC-ATTRIBUTION unit tests and placing them in test_results/${timestamp}..."
cd src
gotestsum --debug --junitfile ../test_results/${timestamp}/junit.xml -- -p 1 -race ./... -coverprofile ../test_results/${timestamp}/testcoverage.out 2>&1 | tee ../test_results/${timestamp}/testresults.out
go tool cover -func ../test_results/${timestamp}/testcoverage.out > ../test_results/${timestamp}/testcov_byfunc.out
echo TOTAL COVERAGE:  $(tail -1 ../test_results/${timestamp}/testcov_byfunc.out | head -1)
go tool cover -html=../test_results/${timestamp}/testcoverage.out -o ../test_results/${timestamp}/testcoverage.html
cp ../test_results/${timestamp}/* ../test_results/latest
echo "DPC-ATTRIBUTION unit tests have completed."