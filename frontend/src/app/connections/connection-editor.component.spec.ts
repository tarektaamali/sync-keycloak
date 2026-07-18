import { TestBed } from '@angular/core/testing';
import { ConnectionEditorComponent } from './connection-editor.component';

describe('ConnectionEditorComponent', () => {
  it('emits a ConnectionRequest on save', () => {
    TestBed.configureTestingModule({ imports: [ConnectionEditorComponent] });
    const f = TestBed.createComponent(ConnectionEditorComponent);
    const c = f.componentInstance;
    const emitted: any[] = [];
    c.save.subscribe((r: any) => emitted.push(r));
    c.model = { name: 'X', type: 'KEYCLOAK', serverUrl: 'http://x', realm: 'x', clientId: 'agent', secret: 's' } as any;
    c.emit();
    expect(emitted[0].name).toBe('X');
    expect(emitted[0].type).toBe('KEYCLOAK');
  });

  it('prefills Samba defaults when creating an LDAP connection', () => {
    TestBed.configureTestingModule({ imports: [ConnectionEditorComponent] });
    const f = TestBed.createComponent(ConnectionEditorComponent);
    const c = f.componentInstance;
    c.useSambaDefaults();
    expect(c.model.type).toBe('LDAP');
    expect(c.model.baseDn).toContain('DC=ORGA');
  });
});
