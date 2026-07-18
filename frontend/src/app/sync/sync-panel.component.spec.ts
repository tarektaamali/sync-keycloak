import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SyncPanelComponent } from './sync-panel.component';

describe('SyncPanelComponent', () => {
  let fixture: ComponentFixture<SyncPanelComponent>;
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [SyncPanelComponent] }).compileComponents();
    fixture = TestBed.createComponent(SyncPanelComponent);
  });

  it('emits a SyncRequest with the selected mode and includeRoles', () => {
    const cmp = fixture.componentInstance;
    const emitted: any[] = [];
    cmp.sync.subscribe(r => emitted.push(r));
    cmp.mode = 'MIRROR';
    cmp.includeRoles = true;
    cmp.emit();
    expect(emitted[0]).toEqual({ mode: 'MIRROR', includeRoles: true, target: undefined });
  });
});
