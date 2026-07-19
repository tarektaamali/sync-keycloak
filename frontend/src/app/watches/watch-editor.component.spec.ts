import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { WatchEditorComponent } from './watch-editor.component';
import { UserWatchRequest } from '../core/models';

describe('WatchEditorComponent', () => {
  beforeEach(() => TestBed.configureTestingModule({
    imports: [WatchEditorComponent, HttpClientTestingModule],
  }));

  it('emits a watch request with safe defaults', () => {
    const fixture = TestBed.createComponent(WatchEditorComponent);
    const cmp = fixture.componentInstance;
    fixture.detectChanges();
    let emitted: UserWatchRequest | undefined;
    cmp.save.subscribe(r => (emitted = r));
    cmp.emit();
    expect(emitted?.onDelete).toBe('DISABLE');
    expect(emitted?.runMode).toBe('REPORT_ONLY');
    expect(emitted?.selectionMode).toBe('LIST');
  });

  it('toggles picked usernames into the payload', () => {
    const fixture = TestBed.createComponent(WatchEditorComponent);
    const cmp = fixture.componentInstance;
    fixture.detectChanges();
    cmp.togglePick('alice');
    cmp.togglePick('bruno');
    cmp.togglePick('alice');       // toggling off
    expect(cmp.model.selectionPayload).toBe('bruno');
  });
});
