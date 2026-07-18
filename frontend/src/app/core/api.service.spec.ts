import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ApiService } from './api.service';

describe('ApiService', () => {
  let api: ApiService; let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule], providers: [ApiService] });
    api = TestBed.inject(ApiService); http = TestBed.inject(HttpTestingController);
  });

  it('posts a keycloak plan request', () => {
    api.keycloakPlan({ sourceConnId: 1, targetConnId: 2, mode: 'CREATE_UPDATE', includeRoles: true }).subscribe();
    const req = http.expectOne('http://localhost:9090/api/keycloak/plan');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.sourceConnId).toBe(1);
    req.flush({ actions: [] });
  });
});
