import {
  Component, ElementRef, OnDestroy, ViewChild, AfterViewInit, NgZone, Input
} from '@angular/core';
import * as THREE from 'three';
import { DronePosition, IncidentEvent } from './drone.service';

const ROME_CENTER = { lat: 41.925, lon: 12.475 };
const SCALE = 1376;
const GRID_SIZE = 15;

// Rome rough outline (simplified polygon, clockwise)
const ROME_OUTLINE = [
  [12.35, 41.85], [12.60, 41.85], [12.60, 42.00], [12.35, 42.00], [12.35, 41.85]
];

function latLonToScene(lat: number, lon: number): { x: number; z: number } {
  const metersPerDegLat = 111000;
  const metersPerDegLon = 111000 * Math.cos((ROME_CENTER.lat * Math.PI) / 180);
  return {
    x: ((lon - ROME_CENTER.lon) * metersPerDegLon) / SCALE,
    z: -((lat - ROME_CENTER.lat) * metersPerDegLat) / SCALE
  };
}

function altToY(alt: number): number {
  return (alt / 200) * 3;
}

const DRONE_COLORS = [
  0x00ffff, 0x00ff88, 0xffff00, 0xff8800,
  0xff00ff, 0x88ff00, 0x0088ff, 0xff0088,
  0x00ffcc, 0xccff00
];

function makePlane(color: number): THREE.Group {
  const mat = new THREE.MeshBasicMaterial({ color });
  const group = new THREE.Group();

  // Fuselage - smaller
  const fuselage = new THREE.Mesh(
    new THREE.CylinderGeometry(0.008, 0.014, 0.13, 8),
    mat
  );
  fuselage.rotation.x = Math.PI / 2;
  group.add(fuselage);

  // Wings
  const wings = new THREE.Mesh(
    new THREE.BoxGeometry(0.16, 0.005, 0.03),
    mat
  );
  group.add(wings);

  // Tail vertical
  const tailV = new THREE.Mesh(
    new THREE.BoxGeometry(0.005, 0.025, 0.025),
    mat
  );
  tailV.position.set(0, 0.012, 0.057);
  group.add(tailV);

  // Tail horizontal
  const tailH = new THREE.Mesh(
    new THREE.BoxGeometry(0.055, 0.005, 0.02),
    mat
  );
  tailH.position.set(0, 0, 0.057);
  group.add(tailH);

  return group;
}

function makeLabel(text: string, color: number): THREE.Sprite {
  const canvas = document.createElement('canvas');
  canvas.width = 128; canvas.height = 32;
  const ctx = canvas.getContext('2d')!;
  ctx.clearRect(0, 0, 128, 32);
  ctx.font = 'bold 18px monospace';
  ctx.fillStyle = '#' + color.toString(16).padStart(6, '0');
  ctx.fillText(text, 3, 22);
  const tex = new THREE.CanvasTexture(canvas);
  const sprite = new THREE.Sprite(new THREE.SpriteMaterial({
    map: tex, transparent: true, depthTest: false, depthWrite: false
  }));
  sprite.scale.set(0.6, 0.15, 1);
  sprite.renderOrder = 999;
  return sprite;
}

const MAX_TRAIL = 10000;

interface DroneObj {
  group: THREE.Group;
  label: THREE.Sprite;
  color: number;
  targetPos: THREE.Vector3;
  targetDir: THREE.Vector3;
  // For GeoJSON export
  trail: { lat: number; lon: number; alt: number }[];
  id: string;
}

@Component({
  selector: 'app-scene3d',
  standalone: true,
  template: `<canvas #canvas style="width:100%;height:100%;display:block;"></canvas>`,
  styles: [':host { display:block; width:100%; height:100%; }']
})
export class Scene3dComponent implements AfterViewInit, OnDestroy {
  @ViewChild('canvas') canvasRef!: ElementRef<HTMLCanvasElement>;

  private renderer!: THREE.WebGLRenderer;
  private scene!: THREE.Scene;
  private camera!: THREE.PerspectiveCamera;
  private animId!: number;
  droneObjs = new Map<string, DroneObj>();
  private colorIndex = 0;

  private isDragging = false;
  private prevMouse = { x: 0, y: 0 };
  private spherical = { theta: 0.5, phi: 0.8, r: 18 };

  @Input() set positions(val: DronePosition[]) {
    if (!this.scene) return;
    val.forEach(p => this.updateDrone(p));
  }
  @Input() incidents: IncidentEvent[] = [];

  constructor(private ngZone: NgZone) {}

  ngAfterViewInit() {
    this.ngZone.runOutsideAngular(() => this.initScene());
  }

  private initScene() {
    const canvas = this.canvasRef.nativeElement;
    this.renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    this.renderer.setClearColor(0x000000);
    this.renderer.setSize(canvas.clientWidth, canvas.clientHeight);

    this.scene = new THREE.Scene();
    this.camera = new THREE.PerspectiveCamera(55, canvas.clientWidth / canvas.clientHeight, 0.01, 200);
    this.camera.position.set(0, 12, 14);
    this.camera.lookAt(0, 0, 0);

    // Grid
    this.scene.add(new THREE.GridHelper(GRID_SIZE, 20, 0x1a1a1a, 0x111111));

    // Rome border outline at ground level
    const borderPts = ROME_OUTLINE.map(([lon, lat]) => {
      const { x, z } = latLonToScene(lat, lon);
      return new THREE.Vector3(x, 0.02, z);
    });
    const borderGeo = new THREE.BufferGeometry().setFromPoints(borderPts);
    this.scene.add(new THREE.Line(borderGeo, new THREE.LineBasicMaterial({ color: 0x00ff44, opacity: 0.5, transparent: true })));

    this.scene.add(new THREE.AmbientLight(0xffffff, 2));

    this.setupOrbit();
    this.setupResize();
    this.animate();
  }

  private updateDrone(pos: DronePosition) {
    const id = String(pos.id);
    const { x, z } = latLonToScene(pos.lat, pos.lon);
    const y = altToY(pos.altitude_m);

    let obj = this.droneObjs.get(id);
    if (!obj) obj = this.createDroneObj(id);

    obj.targetPos.set(x, y, z);
    obj.targetDir.set(pos.dx, pos.dz, -pos.dy).normalize();

    // Record trail for GeoJSON
    obj.trail.push({ lat: pos.lat, lon: pos.lon, alt: pos.altitude_m });
    if (obj.trail.length > MAX_TRAIL) obj.trail.shift();
  }

  private createDroneObj(id: string): DroneObj {
    const color = DRONE_COLORS[this.colorIndex++ % DRONE_COLORS.length];
    const group = makePlane(color);
    const label = makeLabel(`D${id}`, color);
    this.scene.add(group, label);

    const obj: DroneObj = {
      group, label, color, id,
      targetPos: new THREE.Vector3(0, 0, 0),
      targetDir: new THREE.Vector3(0, 0, -1),
      trail: []
    };
    this.droneObjs.set(id, obj);
    return obj;
  }

  exportGeoJSON(id: string): string {
    const obj = this.droneObjs.get(id);
    if (!obj || obj.trail.length === 0) return '{}';
    const coords = obj.trail.map(p => [p.lon, p.lat, p.alt]);
    return JSON.stringify({
      type: 'FeatureCollection',
      features: [{
        type: 'Feature',
        properties: { drone: `D${id}`, points: coords.length },
        geometry: { type: 'LineString', coordinates: coords }
      }]
    }, null, 2);
  }

  exportAllGeoJSON(): string {
    const features = Array.from(this.droneObjs.values())
      .filter(obj => obj.trail.length > 0)
      .map(obj => ({
        type: 'Feature',
        properties: { drone: `D${obj.id}` },
        geometry: {
          type: 'LineString',
          coordinates: obj.trail.map(p => [p.lon, p.lat, p.alt])
        }
      }));
    return JSON.stringify({ type: 'FeatureCollection', features }, null, 2);
  }

  private setupOrbit() {
    const canvas = this.canvasRef.nativeElement;
    canvas.addEventListener('mousedown', e => { this.isDragging = true; this.prevMouse = { x: e.clientX, y: e.clientY }; });
    canvas.addEventListener('mouseup', () => { this.isDragging = false; });
    canvas.addEventListener('mouseleave', () => { this.isDragging = false; });
    canvas.addEventListener('mousemove', e => {
      if (!this.isDragging) return;
      this.spherical.theta -= (e.clientX - this.prevMouse.x) * 0.005;
      this.spherical.phi = Math.max(0.1, Math.min(Math.PI / 2, this.spherical.phi - (e.clientY - this.prevMouse.y) * 0.005));
      this.prevMouse = { x: e.clientX, y: e.clientY };
    });
    canvas.addEventListener('wheel', e => {
      this.spherical.r = Math.max(4, Math.min(40, this.spherical.r + e.deltaY * 0.02));
    });
  }

  private setupResize() {
    window.addEventListener('resize', () => {
      const canvas = this.canvasRef.nativeElement;
      this.renderer.setSize(canvas.clientWidth, canvas.clientHeight);
      this.camera.aspect = canvas.clientWidth / canvas.clientHeight;
      this.camera.updateProjectionMatrix();
    });
  }

  private _quat = new THREE.Quaternion();

  private animate() {
    this.animId = requestAnimationFrame(() => this.animate());
    const { theta, phi, r } = this.spherical;
    this.camera.position.set(
      r * Math.sin(phi) * Math.sin(theta),
      r * Math.cos(phi),
      r * Math.sin(phi) * Math.cos(theta)
    );
    this.camera.lookAt(0, 1, 0);

    const alpha = 0.15;
    this.droneObjs.forEach(obj => {
      obj.group.position.lerp(obj.targetPos, alpha);
      if (obj.targetDir.lengthSq() > 0.001) {
        this._quat.setFromUnitVectors(new THREE.Vector3(0, 0, -1), obj.targetDir.clone().normalize());
        obj.group.quaternion.slerp(this._quat, alpha);
      }
      obj.label.position.set(obj.group.position.x, obj.group.position.y + 0.18, obj.group.position.z);
    });

    this.renderer.render(this.scene, this.camera);
  }

  ngOnDestroy() {
    cancelAnimationFrame(this.animId);
    this.renderer?.dispose();
  }
}
