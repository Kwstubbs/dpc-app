package router

import (
	"net/http"
	"strings"

	"github.com/go-chi/chi/middleware"

	"github.com/CMSgov/dpc/api/auth"
	middleware2 "github.com/CMSgov/dpc/api/middleware"
	v2 "github.com/CMSgov/dpc/api/v2"

	"github.com/go-chi/chi"
)

// NewDPCAPIRouter function that builds the router using chi
func NewDPCAPIRouter(oc v2.Controller, mc v2.ReadController, gc v2.Controller, dc v2.FileController, jc v2.JobController) http.Handler {
	r := chi.NewRouter()
	r.Use(middleware2.Logging())
	r.Use(middleware2.RequestIPCtx)
	fileServer(r, "/v2/swagger", http.Dir("../swaggerui"))
	r.
		With(middleware2.Sanitize).
		Route("/v2", func(r chi.Router) {
			r.Use(middleware.SetHeader("Content-Type", "application/fhir+json; charset=UTF-8"))
			r.Get("/metadata", mc.Read)
			r.Route("/Organization", func(r chi.Router) {
				r.Route("/{organizationID}", func(r chi.Router) {
					r.Use(middleware2.OrganizationCtx)
					r.With(middleware2.FHIRModel).Get("/", oc.Read)
					r.Delete("/", oc.Delete)
					r.With(middleware2.FHIRFilter, middleware2.FHIRModel).Put("/", oc.Update)
				})
				r.With(middleware2.FHIRFilter, middleware2.FHIRModel).Post("/", oc.Create)
			})
			r.Route("/Group", func(r chi.Router) {
				r.Use(middleware2.AuthCtx)
				r.With(middleware2.FHIRFilter, middleware2.FHIRModel).Post("/", gc.Create)
				r.Route("/{groupID}", func(r chi.Router) {
					r.Use(middleware2.RequestURLCtx)
					r.Use(middleware2.GroupCtx)
					r.Use(middleware2.ExportTypesParamCtx)
					r.Use(middleware2.ExportSinceParamCtx)
					r.Get("/$export", gc.Export)
				})
			})
			r.Route("/Jobs", func(r chi.Router) {
				r.Use(middleware.SetHeader("Content-Type", "application/json; charset=UTF-8"))
				r.Use(middleware2.AuthCtx)
				r.With(middleware2.JobCtx).Get("/{jobID}", jc.Status)
			})
			r.Route("/Data", func(r chi.Router) {
				r.Use(middleware2.AuthCtx)
				r.With(middleware2.FileNameCtx).Get("/{fileName}", dc.GetFile)
			})
		})
	r.Post("/auth/token", auth.GetAuthToken)
	r.Get("/auth/welcome", auth.Welcome)
	return r
}

func fileServer(r chi.Router, path string, root http.FileSystem) {
	if strings.ContainsAny(path, "{}*") {
		panic("FileServer does not permit URL parameters.")
	}

	fs := http.StripPrefix(path, http.FileServer(root))

	if path != "/" && path[len(path)-1] != '/' {
		r.Get(path, http.RedirectHandler(path+"/", 301).ServeHTTP)
		path += "/"
	}
	path += "*"

	r.Get(path, func(w http.ResponseWriter, r *http.Request) {
		fs.ServeHTTP(w, r)
	})
}
