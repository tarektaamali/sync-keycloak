import { Component, Input } from '@angular/core';
@Component({
  selector: 'help-tooltip', standalone: true,
  template: `<span class="help-tip text-primary" [title]="text">&#9432;</span>`,
})
export class HelpTooltipComponent { @Input() text = ''; }
