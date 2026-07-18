import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { UserDto } from '../core/models';

@Component({
  selector: 'user-table', standalone: true, imports: [CommonModule],
  template: `
    <table>
      <tr><th>Username</th><th>Email</th><th>Name</th><th>Enabled</th><th>Roles</th></tr>
      <tr *ngFor="let u of users">
        <td>{{u.username}}</td><td>{{u.email}}</td><td>{{u.firstName}} {{u.lastName}}</td>
        <td>{{u.enabled ? 'yes' : 'no'}}</td><td>{{u.roles.join(', ')}}</td>
      </tr>
    </table>`,
})
export class UserTableComponent { @Input() users: UserDto[] = []; }
