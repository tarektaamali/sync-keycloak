import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { ScheduleEditorComponent } from './schedule-editor.component';

describe('ScheduleEditorComponent', () => {
  it('builds a ScheduleRequest from the model', () => {
    TestBed.configureTestingModule({ imports: [ScheduleEditorComponent, HttpClientTestingModule] });
    const c = TestBed.createComponent(ScheduleEditorComponent).componentInstance;
    c.model = { name: 'Nightly', type: 'KEYCLOAK', sourceConnId: 1, targetConnId: 2,
      mode: 'CREATE_UPDATE', includeRoles: true, cron: '0 0 2 * * ?', enabled: true };
    const emitted: any[] = [];
    c.save.subscribe((r: any) => emitted.push(r));
    c.emit();
    expect(emitted[0].cron).toBe('0 0 2 * * ?');
    expect(emitted[0].type).toBe('KEYCLOAK');
  });
});
