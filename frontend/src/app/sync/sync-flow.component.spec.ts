import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { SyncFlowComponent } from './sync-flow.component';

describe('SyncFlowComponent', () => {
  it('builds a SyncRunRequest from selections', () => {
    TestBed.configureTestingModule({ imports: [SyncFlowComponent, HttpClientTestingModule] });
    const c = TestBed.createComponent(SyncFlowComponent).componentInstance;
    c.sourceId = 1; c.targetId = 2; c.selectedMode = 'MIRROR'; c.includeRoles = true;
    expect(c.buildRequest()).toEqual({ sourceConnId: 1, targetConnId: 2, mode: 'MIRROR', includeRoles: true });
  });
});
