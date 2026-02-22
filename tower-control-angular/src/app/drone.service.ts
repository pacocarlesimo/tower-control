import { Injectable, OnDestroy } from '@angular/core';
import { Subject, BehaviorSubject } from 'rxjs';

export interface DronePosition {
  id: string;
  lat: number;
  lon: number;
  altitude_m: number;
  speed_ms: number;
  dx: number;
  dy: number;
  dz: number;
  timestamp: string;
}

export interface RerouteEvent {
  id: string;
  dx: number;
  dy: number;
  dz: number;
}

export interface IncidentEvent {
  drone1: string;
  drone2: string;
  lat: number;
  lon: number;
  altitude_m: number;
}

export interface WsMessage {
  type: 'position' | 'reroute' | 'incident';
  data: DronePosition | RerouteEvent | IncidentEvent;
}

@Injectable({ providedIn: 'root' })
export class DroneService implements OnDestroy {
  private ws: WebSocket | null = null;
  private reconnectTimer: any;

  position$ = new Subject<DronePosition>();
  reroute$ = new Subject<RerouteEvent>();
  incident$ = new Subject<IncidentEvent>();
  connected$ = new BehaviorSubject<boolean>(false);

  droneStates = new Map<string, DronePosition>();

  connect(url = 'ws://localhost:8080/live') {
    if (this.ws) return;
    this.ws = new WebSocket(url);

    this.ws.onopen = () => {
      this.connected$.next(true);
      clearTimeout(this.reconnectTimer);
    };

    this.ws.onmessage = (event) => {
      try {
        const msg: WsMessage = JSON.parse(event.data);
        if (msg.type === 'position') {
          const pos = msg.data as DronePosition;
          this.droneStates.set(pos.id, pos);
          this.position$.next(pos);
        } else if (msg.type === 'reroute') {
          this.reroute$.next(msg.data as RerouteEvent);
        } else if (msg.type === 'incident') {
          this.incident$.next(msg.data as IncidentEvent);
        }
      } catch (e) { /* ignore */ }
    };

    this.ws.onclose = () => {
      this.connected$.next(false);
      this.ws = null;
      this.reconnectTimer = setTimeout(() => this.connect(url), 3000);
    };

    this.ws.onerror = () => {
      this.ws?.close();
    };
  }

  ngOnDestroy() {
    clearTimeout(this.reconnectTimer);
    this.ws?.close();
  }
}
