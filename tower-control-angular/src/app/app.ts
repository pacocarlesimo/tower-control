import { Component, OnInit, OnDestroy, signal, computed, ViewChild, ElementRef, HostListener } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription } from 'rxjs';
import { DroneService, DronePosition, IncidentEvent } from './drone.service';
import { Scene3dComponent } from './scene3d.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, Scene3dComponent],
  templateUrl: './app.html',
  styleUrl: './app.scss'
})
export class App implements OnInit, OnDestroy {
  private subs = new Subscription();

  positions     = signal<DronePosition[]>([]);
  incidents     = signal<IncidentEvent[]>([]);
  connected     = signal(false);
  totalMessages = signal(0);
  incidentCount = signal(0);

  droneIds = computed(() => Array.from(this.scene3d?.droneObjs.keys() ?? []).sort());

  private tickPositions: DronePosition[] = [];
  private pendingIncidents: IncidentEvent[] = [];

  @ViewChild('hud') hudRef!: ElementRef<HTMLDivElement>;
  @ViewChild('scene3d') scene3d!: Scene3dComponent;

  private dragging = false;
  private dragOffsetX = 0;
  private dragOffsetY = 0;

  constructor(public droneService: DroneService) {}

  ngOnInit() {
    this.droneService.connect();

    this.subs.add(this.droneService.connected$.subscribe(c => this.connected.set(c)));

    this.subs.add(this.droneService.position$.subscribe(pos => {
      this.totalMessages.update(n => n + 1);
      this.tickPositions = [...this.tickPositions.filter(p => p.id !== pos.id), pos];
      if (this.totalMessages() % 5 === 0) {
        this.positions.set([...this.tickPositions]);
      }
    }));

    this.subs.add(this.droneService.incident$.subscribe(inc => {
      this.incidentCount.update(n => n + 1);
      this.pendingIncidents = [...this.pendingIncidents, inc];
      this.incidents.set([...this.pendingIncidents]);
      setTimeout(() => {
        this.pendingIncidents = this.pendingIncidents.filter(i => i !== inc);
      }, 3000);
    }));
  }

  exportDrone(id: string) {
    const json = this.scene3d.exportGeoJSON(id);
    this.download(`drone_${id}.geojson`, json);
  }

  exportAll() {
    const json = this.scene3d.exportAllGeoJSON();
    this.download('all_drones.geojson', json);
  }

  private download(filename: string, content: string) {
    const blob = new Blob([content], { type: 'application/json' });
    const url  = URL.createObjectURL(blob);
    const a    = document.createElement('a');
    a.href = url; a.download = filename; a.click();
    URL.revokeObjectURL(url);
  }

  startDrag(event: MouseEvent) {
    this.dragging = true;
    const rect = this.hudRef.nativeElement.getBoundingClientRect();
    this.dragOffsetX = event.clientX - rect.left;
    this.dragOffsetY = event.clientY - rect.top;
    event.preventDefault();
  }

  @HostListener('window:mousemove', ['$event'])
  onMove(event: MouseEvent) {
    if (!this.dragging) return;
    const hud = this.hudRef.nativeElement;
    hud.style.left = (event.clientX - this.dragOffsetX) + 'px';
    hud.style.top  = (event.clientY - this.dragOffsetY) + 'px';
  }

  @HostListener('window:mouseup')
  stopDrag() { this.dragging = false; }

  ngOnDestroy() { this.subs.unsubscribe(); }
}
