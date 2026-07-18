import { Component } from '@angular/core';
@Component({
  selector: 'help-text', standalone: true,
  template: `<small class="text-muted d-block mt-1"><ng-content></ng-content></small>`,
})
export class HelpTextComponent {}
