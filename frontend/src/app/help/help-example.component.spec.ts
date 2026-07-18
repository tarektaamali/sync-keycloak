import { TestBed } from '@angular/core/testing';
import { HelpExampleComponent } from './help-example.component';

describe('HelpExampleComponent', () => {
  it('renders a details element with the given title', () => {
    TestBed.configureTestingModule({ imports: [HelpExampleComponent] });
    const f = TestBed.createComponent(HelpExampleComponent);
    f.componentInstance.title = 'See example';
    f.detectChanges();
    const summary = f.nativeElement.querySelector('summary');
    expect(summary.textContent).toContain('See example');
    expect(f.nativeElement.querySelector('details')).toBeTruthy();
  });
});
