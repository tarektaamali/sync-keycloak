import { Component, Input } from '@angular/core';
@Component({
  selector: 'help-example', standalone: true,
  template: `
    <details class="mt-1">
      <summary class="text-primary small" style="cursor:pointer">{{ title }}</summary>
      <div class="border rounded bg-light p-2 mt-1 small"><ng-content></ng-content></div>
    </details>`,
})
export class HelpExampleComponent { @Input() title = 'See example'; }
