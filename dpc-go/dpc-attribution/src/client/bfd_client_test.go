package client_test

import (
	"compress/gzip"
	"fmt"
	"io"
	"math/rand"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/CMSgov/dpc/attribution/client"
	"github.com/CMSgov/dpc/attribution/conf"
	models "github.com/CMSgov/dpc/attribution/model/fhir"
	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/suite"
)

const (
	clientIDHeader    = "BULK-CLIENTID"
	jobIDHeader       = "BULK-JOBID"
	oldClientIDHeader = "DPC-JOBID"
	oldJobIDHeader    = "DPC-CMSID"
)

type BfdTestSuite struct {
	suite.Suite
}

type BfdRequestTestSuite struct {
	BfdTestSuite
	bbClient *client.BfdClient
	ts       *httptest.Server
}

var (
	ts200, ts500 *httptest.Server
	now          = time.Now()
	nowFormatted = url.QueryEscape(now.Format(time.RFC3339Nano))
	since        = "gt2020-02-14"
	claimsDate   = client.ClaimsWindow{LowerBound: time.Date(2017, 12, 31, 0, 0, 0, 0, time.UTC),
		UpperBound: time.Date(2020, 12, 31, 0, 0, 0, 0, time.UTC)}
)

func (s *BfdTestSuite) SetupSuite() {
	conf.NewConfig("../../configs")
	conf.SetEnv(s.T(), "bfd.clientCertFile", "../shared_files/decrypted/bfd-dev-test-cert.pem")
	conf.SetEnv(s.T(), "bfd.clientKeyFile", "../shared_files/decrypted/bfd-dev-test-key.pem")
	conf.SetEnv(s.T(), "bfd.clientCAFile", "../shared_files/decrypted/bfd-dev-test-ca-file.crt")
	conf.SetEnv(s.T(), "bfd.requestRetryIntervalMS", "10")
	conf.SetEnv(s.T(), "bfd.timeoutMS", "2000")
}

func (s *BfdRequestTestSuite) SetupSuite() {
	ts200 = httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		handlerFunc(w, r, false)
	}))

	ts500 = httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "Some server error", http.StatusInternalServerError)
	}))
}

func (s *BfdRequestTestSuite) BeforeTest(suiteName, testName string) {
	conf.NewConfig("../../configs")
	if strings.Contains(testName, "500") {
		s.ts = ts500
	} else {
		s.ts = ts200
	}

	config := client.BfdConfig{
		BfdServer: s.ts.URL,
	}
	if bfdClient, err := client.NewBfdClient(config); err != nil {
		s.Fail("Failed to create BFD client", err)
	} else {
		s.bbClient = bfdClient
	}
}

/* Tests for creating client and other functions that don't make requests */
func (s *BfdTestSuite) TestNewBfdClientNoCertFile() {
	origCertFile := conf.GetAsString("bfd.clientCertFile")
	defer conf.SetEnv(s.T(), "bfd.clientCertFile", origCertFile)

	assert := assert.New(s.T())

	_ = conf.UnsetEnv(s.T(), "bfd.clientCertFile")
	bbc, err := client.NewBfdClient(client.NewConfig(""))
	assert.Nil(bbc)
	assert.EqualError(err, "could not load BFD keypair: open : no such file or directory")

	conf.SetEnv(s.T(), "bfd.clientCertFile", "foo.pem")
	bbc, err = client.NewBfdClient(client.NewConfig(""))
	assert.Nil(bbc)
	assert.EqualError(err, "could not load BFD keypair: open foo.pem: no such file or directory")
}

func (s *BfdTestSuite) TestNewBfdClientInvalidCertFile() {
	origCertFile := conf.GetAsString("bfd.clientCertFile")
	defer conf.SetEnv(s.T(), "bfd.clientCertFile", origCertFile)

	assert := assert.New(s.T())

	conf.SetEnv(s.T(), "bfd.clientCertFile", "testdata/emptyFile.pem")
	bbc, err := client.NewBfdClient(client.NewConfig(""))
	assert.Nil(bbc)
	assert.EqualError(err, "could not load BFD keypair: tls: failed to find any PEM data in certificate input")

	conf.SetEnv(s.T(), "bfd.clientCertFile", "testdata/badPublic.pem")
	bbc, err = client.NewBfdClient(client.NewConfig(""))
	assert.Nil(bbc)
	assert.EqualError(err, "could not load BFD keypair: tls: failed to find any PEM data in certificate input")
}

func (s *BfdTestSuite) TestNewBfdClientNoKeyFile() {
	origKeyFile := conf.GetAsString("bfd.clientKeyFile")
	defer conf.SetEnv(s.T(), "bfd.clientKeyFile", origKeyFile)

	assert := assert.New(s.T())

	_ = conf.UnsetEnv(s.T(), "bfd.clientKeyFile")
	bbc, err := client.NewBfdClient(client.NewConfig(""))
	assert.Nil(bbc)
	assert.EqualError(err, "could not load BFD keypair: open : no such file or directory")

	conf.SetEnv(s.T(), "bfd.clientKeyFile", "foo.pem")
	bbc, err = client.NewBfdClient(client.NewConfig(""))
	assert.Nil(bbc)
	assert.EqualError(err, "could not load BFD keypair: open foo.pem: no such file or directory")
}

func (s *BfdTestSuite) TestNewBfdClientInvalidKeyFile() {
	origKeyFile := conf.GetAsString("bfd.clientKeyFile")
	defer conf.SetEnv(s.T(), "bfd.clientKeyFile", origKeyFile)

	assert := assert.New(s.T())

	conf.SetEnv(s.T(), "bfd.clientKeyFile", "testdata/emptyFile.pem")
	bbc, err := client.NewBfdClient(client.NewConfig(""))
	assert.Nil(bbc)
	assert.EqualError(err, "could not load BFD keypair: tls: failed to find any PEM data in key input")

	conf.SetEnv(s.T(), "bfd.clientKeyFile", "testdata/badPublic.pem")
	bbc, err = client.NewBfdClient(client.NewConfig(""))
	assert.Nil(bbc)
	assert.EqualError(err, "could not load BFD keypair: tls: failed to find any PEM data in key input")
}

func (s *BfdTestSuite) TestNewBfdClientNoCAFile() {
	origCAFile := conf.GetAsString("bfd.clientCAFile")
	origCheckCert := conf.GetAsString("bfd.checkCert")
	defer func() {
		conf.SetEnv(s.T(), "bfd.clientCAFile", origCAFile)
		conf.SetEnv(s.T(), "bfd.checkCert", origCheckCert)
	}()

	assert := assert.New(s.T())

	_ = conf.UnsetEnv(s.T(), "bfd.clientCAFile")
	_ = conf.UnsetEnv(s.T(), "bfd.checkCert")
	bbc, err := client.NewBfdClient(client.NewConfig(""))
	assert.Nil(bbc)
	assert.EqualError(err, "could not read CA file: read .: is a directory")

	conf.SetEnv(s.T(), "bfd.clientCAFile", "foo.pem")
	bbc, err = client.NewBfdClient(client.NewConfig(""))
	assert.Nil(bbc)
	assert.EqualError(err, "could not read CA file: open foo.pem: no such file or directory")
}

func (s *BfdTestSuite) TestNewBfdClientInvalidCAFile() {
	origCAFile := conf.GetAsString("bfd.clientCAFile")
	origCheckCert := conf.GetAsString("bfd.checkCert")
	defer func() {
		conf.SetEnv(s.T(), "bfd.clientCAFile", origCAFile)
		conf.SetEnv(s.T(), "bfd.checkCert", origCheckCert)
	}()

	assert := assert.New(s.T())

	conf.SetEnv(s.T(), "bfd.clientCAFile", "testdata/emptyFile.pem")
	_ = conf.UnsetEnv(s.T(), "bfd.checkCert")
	bbc, err := client.NewBfdClient(client.NewConfig(""))
	assert.Nil(bbc)
	assert.EqualError(err, "could not append CA certificate(s)")

	conf.SetEnv(s.T(), "bfd.clientCAFile", "testdata/badPublic.pem")
	bbc, err = client.NewBfdClient(client.NewConfig(""))
	assert.Nil(bbc)
	assert.EqualError(err, "could not append CA certificate(s)")
}

func (s *BfdTestSuite) TestGetDefaultParams() {
	params := client.GetDefaultParams()
	assert.Equal(s.T(), "application/fhir+json", params.Get("_format"))
	assert.Equal(s.T(), "", params.Get("patient"))
	assert.Equal(s.T(), "", params.Get("beneficiary"))

}

/* Tests that make requests, using clients configured with the 200 response and 500 response httptest.Servers initialized in SetupSuite() */
func (s *BfdRequestTestSuite) TestGetPatient() {
	p, err := s.bbClient.GetPatient("012345", "543210", "A0000", "", now)
	assert.Nil(s.T(), err)
	assert.Equal(s.T(), 1, len(p.Entries))
	assert.Equal(s.T(), "20000000000001", p.Entries[0]["resource"].(map[string]interface{})["id"])
}

func (s *BfdRequestTestSuite) TestGetPatient_500() {
	p, err := s.bbClient.GetPatient("012345", "543210", "A0000", "", now)
	assert.Regexp(s.T(), `BFD request failed \d+ time\(s\) failed to get bundle response`, err.Error())
	assert.Nil(s.T(), p)
}
func (s *BfdRequestTestSuite) TestGetCoverage() {
	c, err := s.bbClient.GetCoverage("012345", "543210", "A0000", since, now)
	assert.Nil(s.T(), err)
	assert.Equal(s.T(), 3, len(c.Entries))
	assert.Equal(s.T(), "part-b-20000000000001", c.Entries[1]["resource"].(map[string]interface{})["id"])
}

func (s *BfdRequestTestSuite) TestGetCoverage_500() {
	c, err := s.bbClient.GetCoverage("012345", "543210", "A0000", since, now)
	assert.Regexp(s.T(), `BFD request failed \d+ time\(s\) failed to get bundle response`, err.Error())
	assert.Nil(s.T(), c)
}

func (s *BfdRequestTestSuite) TestGetExplanationOfBenefit() {
	e, err := s.bbClient.GetExplanationOfBenefit("012345", "543210", "A0000", "", now, client.ClaimsWindow{})
	assert.Nil(s.T(), err)
	assert.Equal(s.T(), 33, len(e.Entries))
	assert.Equal(s.T(), "carrier-10525061996", e.Entries[3]["resource"].(map[string]interface{})["id"])
}

func (s *BfdRequestTestSuite) TestGetExplanationOfBenefit_500() {
	e, err := s.bbClient.GetExplanationOfBenefit("012345", "543210", "A0000", "", now, client.ClaimsWindow{})
	assert.Regexp(s.T(), `BFD request failed \d+ time\(s\) failed to get bundle response`, err.Error())
	assert.Nil(s.T(), e)
}

func (s *BfdRequestTestSuite) TestGetMetadata() {
	m, err := s.bbClient.GetMetadata()
	assert.Nil(s.T(), err)
	assert.Contains(s.T(), m, `"resourceType": "CapabilityStatement"`)
	assert.NotContains(s.T(), m, "excludeSAMHSA=true")
}

func (s *BfdRequestTestSuite) TestGetMetadata_500() {
	p, err := s.bbClient.GetMetadata()
	assert.Regexp(s.T(), `BFD request failed \d+ time\(s\) failed to get response`, err.Error())
	assert.Equal(s.T(), "", p)
}

func (s *BfdRequestTestSuite) TestGetPatientByIdentifierHash() {
	p, err := s.bbClient.GetPatientByIdentifierHash("hashedIdentifier")
	assert.Nil(s.T(), err)
	assert.Contains(s.T(), p, `"id": "20000000000001"`)
}

// Sample values from https://confluence.cms.gov/pages/viewpage.action?spaceKey=BB&title=Getting+Started+with+Blue+Button+2.0%27s+Backend#space-menu-link-content
func (s *BfdTestSuite) TestHashIdentifier() {
	assert.NotZero(s.T(), conf.GetAsString("bfd.hashPepper"))
	HICN := "1000067585"
	HICNHash := client.HashIdentifier(HICN)
	// This test will only be valid for this pepper.  If it is different in different environments we will need different checks
	if conf.GetAsString("bfd.hashPepper") == "b8ebdcc47fdd852b8b0201835c6273a9177806e84f2d9dc4f7ecaff08681e86d74195c6aef2db06d3d44c9d0b8f93c3e6c43d90724b605ac12585b9ab5ee9c3f00d5c0d284e6b8e49d502415c601c28930637b58fdca72476e31c22ad0f24ecd761020d6a4bcd471f0db421d21983c0def1b66a49a230f85f93097e9a9a8e0a4f4f0add775213cbf9ecfc1a6024cb021bd1ed5f4981a4498f294cca51d3939dfd9e6a1045350ddde7b6d791b4d3b884ee890d4c401ef97b46d1e57d40efe5737248dd0c4cec29c23c787231c4346cab9bb973f140a32abaa0a2bd5c0b91162f8d2a7c9d3347aafc76adbbd90ec5bfe617a3584e94bc31047e3bb6850477219a9" {
		assert.Equal(s.T(), "b67baee938a551f06605ecc521cc329530df4e088e5a2d84bbdcc047d70faff4", HICNHash)
	}
	HICN = "123456789"
	HICNHash = client.HashIdentifier(HICN)
	assert.NotEqual(s.T(), "b67baee938a551f06605ecc521cc329530df4e088e5a2d84bbdcc047d70faff4", HICNHash)

	MBI := "1000067585"
	MBIHash := client.HashIdentifier(MBI)
	if conf.GetAsString("bfd.hashPepper") == "b8ebdcc47fdd852b8b0201835c6273a9177806e84f2d9dc4f7ecaff08681e86d74195c6aef2db06d3d44c9d0b8f93c3e6c43d90724b605ac12585b9ab5ee9c3f00d5c0d284e6b8e49d502415c601c28930637b58fdca72476e31c22ad0f24ecd761020d6a4bcd471f0db421d21983c0def1b66a49a230f85f93097e9a9a8e0a4f4f0add775213cbf9ecfc1a6024cb021bd1ed5f4981a4498f294cca51d3939dfd9e6a1045350ddde7b6d791b4d3b884ee890d4c401ef97b46d1e57d40efe5737248dd0c4cec29c23c787231c4346cab9bb973f140a32abaa0a2bd5c0b91162f8d2a7c9d3347aafc76adbbd90ec5bfe617a3584e94bc31047e3bb6850477219a9" {
		assert.Equal(s.T(), "b67baee938a551f06605ecc521cc329530df4e088e5a2d84bbdcc047d70faff4", MBIHash)
	}
	MBI = "123456789"
	MBIHash = client.HashIdentifier(MBI)
	assert.NotEqual(s.T(), "b67baee938a551f06605ecc521cc329530df4e088e5a2d84bbdcc047d70faff4", MBIHash)
}

func (s *BfdRequestTestSuite) TearDownAllSuite() {
	s.ts.Close()
}

func (s *BfdRequestTestSuite) TestValidateRequest() {
	old := conf.GetAsInt("bfd.clientPageSize")
	defer conf.SetEnv(s.T(), "bfd.clientPageSize", old)
	conf.SetEnv(s.T(), "bfd.clientPageSize", "0") // Need to ensure that requests do not have the _count parameter

	tests := []struct {
		name          string
		funcUnderTest func(bbClient *client.BfdClient, jobID, cmsID string) (interface{}, error)
		// Lighter validation checks since we've already thoroughly tested the methods in other tests
		payloadChecker func(t *testing.T, payload interface{})
		pathCheckers   []func(t *testing.T, req *http.Request)
	}{
		{
			"GetExplanationOfBenefit",
			func(bbClient *client.BfdClient, jobID, cmsID string) (interface{}, error) {
				return bbClient.GetExplanationOfBenefit("patient1", jobID, cmsID, since, now, client.ClaimsWindow{})
			},
			func(t *testing.T, payload interface{}) {
				result, ok := payload.(*models.Bundle)
				assert.True(t, ok)
				assert.NotEmpty(t, result.Entries)
			},
			[]func(*testing.T, *http.Request){
				sinceChecker,
				nowChecker,
				excludeSAMHSAChecker,
				noServiceDateChecker,
				noIncludeAddressFieldsChecker,
				includeTaxNumbersChecker,
			},
		},
		{
			"GetExplanationOfBenefitNoSince",
			func(bbClient *client.BfdClient, jobID, cmsID string) (interface{}, error) {
				return bbClient.GetExplanationOfBenefit("patient1", jobID, cmsID, "", now, client.ClaimsWindow{})
			},
			func(t *testing.T, payload interface{}) {
				result, ok := payload.(*models.Bundle)
				assert.True(t, ok)
				assert.NotEmpty(t, result.Entries)
			},
			[]func(*testing.T, *http.Request){
				noSinceChecker,
				nowChecker,
				excludeSAMHSAChecker,
				noServiceDateChecker,
				noIncludeAddressFieldsChecker,
				includeTaxNumbersChecker,
			},
		},
		{
			"GetExplanationOfBenefitWithUpperBoundServiceDate",
			func(bbClient *client.BfdClient, jobID, cmsID string) (interface{}, error) {
				return bbClient.GetExplanationOfBenefit("patient1", jobID, cmsID, since, now, client.ClaimsWindow{UpperBound: claimsDate.UpperBound})
			},
			func(t *testing.T, payload interface{}) {
				result, ok := payload.(*models.Bundle)
				assert.True(t, ok)
				assert.NotEmpty(t, result.Entries)
			},
			[]func(*testing.T, *http.Request){
				sinceChecker,
				nowChecker,
				excludeSAMHSAChecker,
				serviceDateUpperBoundChecker,
				noServiceDateLowerBoundChecker,
				noIncludeAddressFieldsChecker,
				includeTaxNumbersChecker,
			},
		},
		{
			"GetExplanationOfBenefitWithLowerBoundServiceDate",
			func(bbClient *client.BfdClient, jobID, cmsID string) (interface{}, error) {
				return bbClient.GetExplanationOfBenefit("patient1", jobID, cmsID, since, now, client.ClaimsWindow{LowerBound: claimsDate.LowerBound})
			},
			func(t *testing.T, payload interface{}) {
				result, ok := payload.(*models.Bundle)
				assert.True(t, ok)
				assert.NotEmpty(t, result.Entries)
			},
			[]func(*testing.T, *http.Request){
				sinceChecker,
				nowChecker,
				excludeSAMHSAChecker,
				serviceDateLowerBoundChecker,
				noServiceDateUpperBoundChecker,
				noIncludeAddressFieldsChecker,
				includeTaxNumbersChecker,
			},
		},
		{
			"GetExplanationOfBenefitWithLowerAndUpperBoundServiceDate",
			func(bbClient *client.BfdClient, jobID, cmsID string) (interface{}, error) {
				return bbClient.GetExplanationOfBenefit("patient1", jobID, cmsID, since, now, claimsDate)
			},
			func(t *testing.T, payload interface{}) {
				result, ok := payload.(*models.Bundle)
				assert.True(t, ok)
				assert.NotEmpty(t, result.Entries)
			},
			[]func(*testing.T, *http.Request){
				sinceChecker,
				nowChecker,
				excludeSAMHSAChecker,
				serviceDateLowerBoundChecker,
				serviceDateUpperBoundChecker,
				noIncludeAddressFieldsChecker,
				includeTaxNumbersChecker,
			},
		},
		{
			"GetPatient",
			func(bbClient *client.BfdClient, jobID, cmsID string) (interface{}, error) {
				return bbClient.GetPatient("patient2", jobID, cmsID, since, now)
			},
			func(t *testing.T, payload interface{}) {
				result, ok := payload.(*models.Bundle)
				assert.True(t, ok)
				assert.NotEmpty(t, result.Entries)
			},
			[]func(*testing.T, *http.Request){
				sinceChecker,
				nowChecker,
				noExcludeSAMHSAChecker,
				includeAddressFieldsChecker,
				noIncludeTaxNumbersChecker,
			},
		},
		{
			"GetPatientNoSince",
			func(bbClient *client.BfdClient, jobID, cmsID string) (interface{}, error) {
				return bbClient.GetPatient("patient2", jobID, cmsID, "", now)
			},
			func(t *testing.T, payload interface{}) {
				result, ok := payload.(*models.Bundle)
				assert.True(t, ok)
				assert.NotEmpty(t, result.Entries)
			},
			[]func(*testing.T, *http.Request){
				noSinceChecker,
				nowChecker,
				noExcludeSAMHSAChecker,
				includeAddressFieldsChecker,
				noIncludeTaxNumbersChecker,
			},
		},
		{
			"GetCoverage",
			func(bbClient *client.BfdClient, jobID, cmsID string) (interface{}, error) {
				return bbClient.GetCoverage("beneID1", jobID, cmsID, since, now)
			},
			func(t *testing.T, payload interface{}) {
				result, ok := payload.(*models.Bundle)
				assert.True(t, ok)
				assert.NotEmpty(t, result.Entries)
			},
			[]func(*testing.T, *http.Request){
				sinceChecker,
				nowChecker,
				noExcludeSAMHSAChecker,
				noIncludeAddressFieldsChecker,
				noIncludeTaxNumbersChecker,
			},
		},
		{
			"GetCoverageNoSince",
			func(bbClient *client.BfdClient, jobID, cmsID string) (interface{}, error) {
				return bbClient.GetCoverage("beneID1", jobID, cmsID, "", now)
			},
			func(t *testing.T, payload interface{}) {
				result, ok := payload.(*models.Bundle)
				assert.True(t, ok)
				assert.NotEmpty(t, result.Entries)
			},
			[]func(*testing.T, *http.Request){
				noSinceChecker,
				nowChecker,
				noExcludeSAMHSAChecker,
				noIncludeAddressFieldsChecker,
				noIncludeTaxNumbersChecker,
			},
		},
		{
			"GetPatientByIdentifierHash",
			func(bbClient *client.BfdClient, jobID, cmsID string) (interface{}, error) {
				return bbClient.GetPatientByIdentifierHash("hashedIdentifier")
			},
			func(t *testing.T, payload interface{}) {
				result, ok := payload.(string)
				assert.True(t, ok)
				assert.NotEmpty(t, result)
			},
			[]func(*testing.T, *http.Request){
				noExcludeSAMHSAChecker,
				noIncludeAddressFieldsChecker,
				noIncludeTaxNumbersChecker,
			},
		},
	}

	for _, tt := range tests {
		s.T().Run(tt.name, func(t *testing.T) {
			var jobID, cmsID string

			// GetPatientByIdentifierHash does not send in jobID and cmsID as arguments
			// so we DO NOT expected the associated headers to be set.
			// Only set the fields if we pass those parameters in.
			if tt.name != "GetPatientByIdentifierHash" {
				jobID = strconv.FormatUint(rand.Uint64(), 10)
				cmsID = strconv.FormatUint(rand.Uint64(), 10)
			}

			tsValidation := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
				o, _ := uuid.Parse(req.Header.Get("BFD-OriginalQueryId"))
				assert.NotNil(t, o)
				assert.Equal(t, "1", req.Header.Get("BFD-OriginalQueryCounter"))

				assert.Empty(t, req.Header.Get("keep-alive"))
				assert.Nil(t, req.Header.Values("X-Forwarded-Proto"))
				assert.Nil(t, req.Header.Values("X-Forwarded-Host"))

				assert.True(t, strings.HasSuffix(req.Header.Get("BFD-OriginalUrl"), req.URL.String()),
					"%s does not end with %s", req.Header.Get("BFD-OriginalUrl"), req.URL.String())
				assert.Equal(t, req.URL.RawQuery, req.Header.Get("BFD-OriginalQuery"))

				assert.Equal(t, jobID, req.Header.Get(jobIDHeader))
				assert.Equal(t, cmsID, req.Header.Get(clientIDHeader))
				assert.Empty(t, req.Header.Get(oldJobIDHeader))
				assert.Empty(t, req.Header.Get(oldClientIDHeader))

				assert.Equal(t, "mbi", req.Header.Get("IncludeIdentifiers"))

				// Verify that we have compression enabled on these HTTP requests.
				// NOTE: This header should not be explicitly set on the client. It should be added by the http.Transport.
				// Details: https://golang.org/src/net/http/transport.go#L2432
				assert.Equal(t, "gzip", req.Header.Get("Accept-Encoding"))

				for _, checker := range tt.pathCheckers {
					checker(t, req)
				}

				handlerFunc(w, req, true)
			}))
			defer tsValidation.Close()

			config := client.BfdConfig{
				BfdServer: tsValidation.URL,
			}
			bbClient, err := client.NewBfdClient(config)
			if err != nil {
				assert.FailNow(t, err.Error())
			}

			data, err := tt.funcUnderTest(bbClient, jobID, cmsID)
			if err != nil {
				assert.FailNow(t, err.Error())
			}

			tt.payloadChecker(t, data)
		})
	}
}

func handlerFunc(w http.ResponseWriter, r *http.Request, useGZIP bool) {
	path := r.URL.Path
	var (
		file *os.File
		err  error
	)
	if strings.Contains(path, "Coverage") {
		file, err = os.Open("testdata/synthetic_beneficiary_data/Coverage")
	} else if strings.Contains(path, "ExplanationOfBenefit") {
		file, err = os.Open("testdata/synthetic_beneficiary_data/ExplanationOfBenefit")
	} else if strings.Contains(path, "metadata") {
		file, err = os.Open("./testdata/Metadata.json")
	} else if strings.Contains(path, "Patient") {
		file, err = os.Open("testdata/synthetic_beneficiary_data/Patient")
	} else {
		err = fmt.Errorf("unrecognized path supplied %s", path)
		http.Error(w, err.Error(), http.StatusBadRequest)
	}

	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	defer file.Close()

	w.Header().Set("Content-Type", r.URL.Query().Get("_format"))

	if useGZIP {
		w.Header().Set("Content-Encoding", "gzip")
		gz := gzip.NewWriter(w)
		defer gz.Close()
		if _, err := io.Copy(gz, file); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
		}
	} else {
		if _, err := io.Copy(w, file); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
		}
	}
}

func noSinceChecker(t *testing.T, req *http.Request) {
	assert.NotContains(t, req.URL.String(), "_lastUpdated=gt")
}
func sinceChecker(t *testing.T, req *http.Request) {
	assert.Contains(t, req.URL.String(), fmt.Sprintf("_lastUpdated=%s", since))
}
func noExcludeSAMHSAChecker(t *testing.T, req *http.Request) {
	assert.NotContains(t, req.URL.String(), "excludeSAMHSA=true")
}
func excludeSAMHSAChecker(t *testing.T, req *http.Request) {
	assert.Contains(t, req.URL.String(), "excludeSAMHSA=true")
}
func nowChecker(t *testing.T, req *http.Request) {
	assert.Contains(t, req.URL.String(), fmt.Sprintf("_lastUpdated=le%s", nowFormatted))
}
func noServiceDateChecker(t *testing.T, req *http.Request) {
	assert.Empty(t, req.URL.Query()["service-date"])
}
func serviceDateUpperBoundChecker(t *testing.T, req *http.Request) {
	// We expect that service date only contains YYYY-MM-DD
	assert.Contains(t, req.URL.Query()["service-date"], fmt.Sprintf("le%s", claimsDate.UpperBound.Format("2006-01-02")))
}
func noServiceDateUpperBoundChecker(t *testing.T, req *http.Request) {
	// We expect that service date only contains YYYY-MM-DD
	assert.NotContains(t, req.URL.Query()["service-date"], fmt.Sprintf("le%s", claimsDate.UpperBound.Format("2006-01-02")))
}
func serviceDateLowerBoundChecker(t *testing.T, req *http.Request) {
	// We expect that service date only contains YYYY-MM-DD
	assert.Contains(t, req.URL.Query()["service-date"], fmt.Sprintf("ge%s", claimsDate.LowerBound.Format("2006-01-02")))
}
func noServiceDateLowerBoundChecker(t *testing.T, req *http.Request) {
	// We expect that service date only contains YYYY-MM-DD
	assert.NotContains(t, req.URL.Query()["service-date"], fmt.Sprintf("ge%s", claimsDate.LowerBound.Format("2006-01-02")))
}
func noIncludeAddressFieldsChecker(t *testing.T, req *http.Request) {
	assert.Empty(t, req.Header.Get("IncludeAddressFields"))
}
func includeAddressFieldsChecker(t *testing.T, req *http.Request) {
	assert.Equal(t, "true", req.Header.Get("IncludeAddressFields"))
}
func noIncludeTaxNumbersChecker(t *testing.T, req *http.Request) {
	assert.Empty(t, req.Header.Get("IncludeTaxNumbers"))
}
func includeTaxNumbersChecker(t *testing.T, req *http.Request) {
	assert.Equal(t, "true", req.Header.Get("IncludeTaxNumbers"))
}

func TestBfdTestSuite(t *testing.T) {
	suite.Run(t, new(BfdTestSuite))
	suite.Run(t, new(BfdRequestTestSuite))
}