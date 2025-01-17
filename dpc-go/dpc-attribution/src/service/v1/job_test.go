package service

import (
	"bytes"
	"context"
	"encoding/json"
	"github.com/CMSgov/dpc/attribution/attributiontest"
	"github.com/CMSgov/dpc/attribution/client"
	v2 "github.com/CMSgov/dpc/attribution/model"
	"io/ioutil"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	"github.com/CMSgov/dpc/attribution/conf"
	middleware2 "github.com/CMSgov/dpc/attribution/middleware"
	"github.com/CMSgov/dpc/attribution/model/v1"
	"github.com/bxcodec/faker/v3"
	"github.com/kinbiko/jsonassert"
	"github.com/pkg/errors"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/suite"
)

type MockJobRepo struct {
	mock.Mock
}

func (m *MockJobRepo) Insert(ctx context.Context, orgID string, b []v1.BatchRequest) (*string, error) {
	args := m.Called(ctx, orgID, b)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*string), args.Error(1)
}

func (m *MockJobRepo) FindBatchesByJobID(id string, orgID string) ([]v1.JobQueueBatch, error) {
	args := m.Called(id, orgID)
	return args.Get(0).([]v1.JobQueueBatch), args.Error(1)
}

func (m *MockJobRepo) FindBatchFilesByBatchID(id string) ([]v1.JobQueueBatchFile, error) {
	args := m.Called(id)
	return args.Get(0).([]v1.JobQueueBatchFile), args.Error(1)
}

func (m *MockJobRepo) GetFileInfo(ctx context.Context, orgID string, fileName string) (*v1.FileInfo, error) {
	args := m.Called(ctx, orgID, fileName)
	return args.Get(0).(*v1.FileInfo), args.Error(1)
}

type MockOrgRepo struct {
	mock.Mock
}

func (m *MockOrgRepo) Insert(ctx context.Context, body []byte) (*v2.Organization, error) {
	args := m.Called(ctx, body)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*v2.Organization), args.Error(1)
}
func (m *MockOrgRepo) FindByID(ctx context.Context, id string) (*v2.Organization, error) {
	args := m.Called(ctx, id)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*v2.Organization), args.Error(1)
}

func (m *MockOrgRepo) FindByNPI(ctx context.Context, npi string) (*v2.Organization, error) {
	args := m.Called(ctx, npi)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*v2.Organization), args.Error(1)
}

func (m *MockOrgRepo) DeleteByID(ctx context.Context, id string) error {
	args := m.Called(ctx, id)
	return args.Error(0)
}
func (m *MockOrgRepo) Update(ctx context.Context, id string, body []byte) (*v2.Organization, error) {
	args := m.Called(ctx, id, body)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*v2.Organization), args.Error(1)
}

type JobServiceV1TestSuite struct {
	suite.Suite
	jr      *MockJobRepo
	or      *MockOrgRepo
	service JobService
	client  *client.MockBfdClient
}

func TestJobServiceV1TestSuite(t *testing.T) {
	suite.Run(t, new(JobServiceV1TestSuite))
}

func (suite *JobServiceV1TestSuite) SetupTest() {
	_ = os.Setenv("DPC_BFD_CLIENTCERTFILE", "../../../shared_files/decrypted/bfd-dev-test-cert.pem")
	_ = os.Setenv("DPC_BFD_CLIENTKEYFILE", "../../../shared_files/decrypted/bfd-dev-test-key.pem")
	_ = os.Setenv("DPC_BFD_CLIENTCAFILE", "../../../shared_files/decrypted/bfd-dev-test-ca-file.crt")

	conf.NewConfig("../../../configs")
	suite.jr = &MockJobRepo{}
	suite.or = &MockOrgRepo{}
	suite.client = &client.MockBfdClient{}
	suite.client.BasePath = "../../client/"
	suite.service = NewJobService(suite.jr, suite.or, suite.client)

	suite.or.On("FindByID", mock.Anything, mock.Anything).Return(attributiontest.OrgResponse(), nil)
	suite.client.On("GetPatient", mock.Anything, mock.Anything, mock.Anything, mock.Anything, mock.Anything).
		Return(suite.client.GetBundleData("Patient", "12345"))
}

func (suite *JobServiceV1TestSuite) TestExport() {
	ja := jsonassert.New(suite.T())

	id := "12345"
	suite.jr.On("Insert", mock.Anything, mock.Anything, mock.Anything).Return(&id, nil)

	exportRequest := v1.ExportRequest{
		GroupID:      faker.UUIDHyphenated(),
		OutputFormat: "",
		Since:        time.Now().Format(middleware2.SinceLayout),
		Type:         "Patient,Coverage,ExplanationOfBenefits",
		MBIs:         []string{faker.UUIDDigit(), faker.UUIDDigit()},
		ProviderNPI:  faker.UUIDHyphenated(),
	}

	b, _ := json.Marshal(exportRequest)

	req := httptest.NewRequest(http.MethodPost, "http://example.com/v2/Job", bytes.NewReader(b))
	ctx := req.Context()
	ctx = context.WithValue(ctx, middleware2.ContextKeyOrganization, "12345")
	req.Header.Set(middleware2.FwdHeader, faker.IPv4())
	req.Header.Set(middleware2.RequestURLHeader, faker.URL())
	req = req.WithContext(ctx)

	w := httptest.NewRecorder()
	suite.service.Export(w, req)

	res := w.Result()

	assert.Equal(suite.T(), http.StatusOK, res.StatusCode)

	resp, _ := ioutil.ReadAll(res.Body)

	ja.Assertf(string(resp), id)
}

func (suite *JobServiceV1TestSuite) TestExportRepoError() {
	ja := jsonassert.New(suite.T())

	suite.jr.On("Insert", mock.Anything, mock.Anything, mock.Anything).Return(nil, errors.New("error"))

	exportRequest := v1.ExportRequest{
		GroupID:      faker.UUIDHyphenated(),
		OutputFormat: "",
		Since:        time.Now().Format(middleware2.SinceLayout),
		Type:         "Patient,Coverage,ExplanationOfBenefits",
		MBIs:         []string{faker.UUIDDigit(), faker.UUIDDigit()},
		ProviderNPI:  faker.UUIDHyphenated(),
	}

	b, _ := json.Marshal(exportRequest)

	req := httptest.NewRequest("GET", "http://example.com/v2/Group/9876/$export", bytes.NewReader(b))
	ctx := req.Context()
	ctx = context.WithValue(ctx, middleware2.ContextKeyOrganization, "12345")
	req.Header.Set(middleware2.FwdHeader, faker.IPv4())
	req.Header.Set(middleware2.RequestURLHeader, faker.URL())
	req = req.WithContext(ctx)

	w := httptest.NewRecorder()

	suite.service.Export(w, req)

	res := w.Result()

	assert.Equal(suite.T(), http.StatusUnprocessableEntity, res.StatusCode)

	resp, _ := ioutil.ReadAll(res.Body)

	ja.Assertf(string(resp), `
	{
        "error": "Unprocessable Entity",
        "message": "error",
        "statusCode": 422
    }`)
}

func (suite *JobServiceV1TestSuite) TestGetBatchesAndFiles() {
	jqb := v1.JobQueueBatch{}
	_ = faker.FakeData(&jqb)

	req := httptest.NewRequest("GET", "http://doesnotmatter.com", nil)
	ctx := req.Context()
	ctx = context.WithValue(ctx, middleware2.ContextKeyOrganization, "12345")
	ctx = context.WithValue(ctx, middleware2.ContextKeyJobID, "54321")
	req = req.WithContext(ctx)

	r := v1.ResourceType("Patient")
	c := v1.HexType("ad09ae2eee0a5111508b072cb8c3eaca49f342df82b7c456bcd04df7612283e77f04f0d55e89a5a235f6636a3a9180169b890f6a3078e200fd9a1ca1574885767ffa30ddf94bc374464cf8c6f1da72c8")
	suite.jr.On("FindBatchesByJobID", mock.MatchedBy(func(passedInJobID string) bool {
		return passedInJobID == "54321"
	}), mock.MatchedBy(func(passedInOrgID string) bool {
		return passedInOrgID == "12345"
	})).Return([]v1.JobQueueBatch{jqb}, nil)

	suite.jr.On("FindBatchFilesByBatchID", mock.MatchedBy(func(passedInBatchID string) bool {
		return passedInBatchID == jqb.BatchID
	})).Return([]v1.JobQueueBatchFile{
		{
			ResourceType: &r,
			BatchID:      jqb.BatchID,
			Sequence:     0,
			FileName:     "testFileName",
			Count:        1,
			Checksum:     &c,
			FileLength:   1234,
		},
	}, nil)

	w := httptest.NewRecorder()
	suite.service.BatchesAndFiles(w, req)
	res := w.Result()

	resp, _ := ioutil.ReadAll(res.Body)
	var batchesAndFiles []v1.BatchAndFiles
	_ = json.Unmarshal(resp, &batchesAndFiles)

	assert.NotNil(suite.T(), batchesAndFiles)
	assert.Len(suite.T(), batchesAndFiles, 1)
	assert.Equal(suite.T(), "testFileName", batchesAndFiles[0].Files[0].FileName)
}
