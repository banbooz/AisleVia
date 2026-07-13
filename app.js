import * as THREE from 'three';

const $ = (id) => document.getElementById(id);
const landing = $('landing');
const unsupported = $('unsupported');
const overlay = $('overlay');
const startARButton = $('startAR');
const supportMessage = $('supportMessage');
const placeEntranceButton = $('placeEntrance');
const placementPanel = $('placementPanel');
const finderPanel = $('finderPanel');
const itemSelect = $('itemSelect');
const statusEl = $('status');
const distanceText = $('distanceText');
const targetName = $('targetName');
const progressBar = $('progressBar');
const toast = $('toast');

const ENTRY_Z = 2.05;
const toEntryCoords = (x, y, z) => new THREE.Vector3(x, y, z - ENTRY_Z);

// Approximate coordinates reconstructed from the photo-based digital twin.
const ITEMS = {
  pringles: {
    label: 'Pringles can', detail: 'coffee table',
    position: toEntryCoords(-0.30, 0.87, -0.62),
    approach: new THREE.Vector2(-0.28, 0.24 - ENTRY_Z),
    radius: 0.18
  },
  baking: {
    label: 'Baking soda', detail: 'bookcase shelf',
    position: toEntryCoords(2.52, 1.14, -1.28),
    approach: new THREE.Vector2(1.95, -1.20 - ENTRY_Z),
    radius: 0.22
  },
  remote: {
    label: 'TV remote', detail: 'left sofa',
    position: toEntryCoords(-2.06, 0.95, 0.71),
    approach: new THREE.Vector2(-1.45, 0.72 - ENTRY_Z),
    radius: 0.20
  },
  books: {
    label: 'Book', detail: 'white bookcase',
    position: toEntryCoords(2.23, 0.73, -1.26),
    approach: new THREE.Vector2(1.93, -1.30 - ENTRY_Z),
    radius: 0.24
  },
  tv: {
    label: 'Television', detail: 'right wall',
    position: toEntryCoords(2.73, 1.18, 0.44),
    approach: new THREE.Vector2(2.15, 0.42 - ENTRY_Z),
    radius: 0.48
  }
};

const room = {
  minX: -3.0,
  maxX: 3.0,
  minZ: -2.18 - ENTRY_Z,
  maxZ: 2.22 - ENTRY_Z
};

// Major furniture footprints, in metres, for route planning.
const obstacles = [
  { name: 'left built-in', x: -2.35, z: -2.07 - ENTRY_Z, w: 1.35, d: 0.55, rot: 0 },
  { name: 'bookcase', x: 2.42, z: -1.67 - ENTRY_Z, w: 0.72, d: 0.34, rot: 0 },
  { name: 'left sofa', x: -2.36, z: 0.55 - ENTRY_Z, w: 1.18, d: 2.08, rot: 0 },
  { name: 'left sofa back', x: -2.80, z: 0.55 - ENTRY_Z, w: 0.28, d: 2.08, rot: 0 },
  { name: 'right settee', x: 1.22, z: -0.82 - ENTRY_Z, w: 1.55, d: 1.16, rot: 0 },
  { name: 'front sofa', x: 0.92, z: 1.92 - ENTRY_Z, w: 1.75, d: 0.72, rot: 0 },
  { name: 'coffee table', x: -0.30, z: -0.58 - ENTRY_Z, w: 1.68, d: 0.78, rot: 0 },
  { name: 'rocking chair', x: -2.35, z: -1.18 - ENTRY_Z, w: 0.72, d: 0.72, rot: 0.12 },
  { name: 'tv stand', x: 2.82, z: 0.44 - ENTRY_Z, w: 0.42, d: 1.72, rot: 0 }
];

let renderer;
let scene;
let camera;
let session = null;
let viewerSpace = null;
let referenceSpace = null;
let hitTestSource = null;
let currentHit = null;
let currentHitMatrix = new THREE.Matrix4();
let originPlaced = false;
let selectedKey = 'pringles';
let routePath = [];
let startRouteDistance = 1;
let lastRoutePosition = new THREE.Vector3(999, 0, 999);
let lastRouteTime = 0;
let basePosition = new THREE.Vector3();
let baseYaw = 0;
let lastToastTimer = 0;

const worldCameraPosition = new THREE.Vector3();
const localCameraPosition = new THREE.Vector3();
const cameraForward = new THREE.Vector3();
const tempQuaternion = new THREE.Quaternion();
const tempScale = new THREE.Vector3();

const root = new THREE.Group();
root.visible = false;
const routeGroup = new THREE.Group();
const targetGroup = new THREE.Group();
root.add(routeGroup, targetGroup);

let reticle;
let highlightCore;
let highlightRings = [];
let guideDashes = [];

function initThree() {
  if (renderer) return;

  scene = new THREE.Scene();
  camera = new THREE.PerspectiveCamera(60, innerWidth / innerHeight, 0.01, 30);
  renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: 'high-performance' });
  renderer.setPixelRatio(Math.min(devicePixelRatio || 1, 2));
  renderer.setSize(innerWidth, innerHeight);
  renderer.setClearColor(0x000000, 0);
  renderer.xr.enabled = true;
  renderer.xr.setReferenceSpaceType('local');
  renderer.domElement.setAttribute('aria-label', 'Augmented reality camera view');
  document.body.insertBefore(renderer.domElement, document.body.firstChild);

  scene.add(root);

  const reticleMaterial = new THREE.MeshBasicMaterial({
    color: 0x2cf58a,
    transparent: true,
    opacity: 0.95,
    side: THREE.DoubleSide,
    depthTest: false,
    depthWrite: false
  });
  reticle = new THREE.Mesh(new THREE.RingGeometry(0.09, 0.125, 48), reticleMaterial);
  reticle.rotation.x = -Math.PI / 2;
  reticle.matrixAutoUpdate = false;
  reticle.visible = false;
  reticle.renderOrder = 100;
  scene.add(reticle);

  const cross = new THREE.Group();
  const crossMat = reticleMaterial.clone();
  const crossA = new THREE.Mesh(new THREE.BoxGeometry(0.20, 0.006, 0.015), crossMat);
  const crossB = new THREE.Mesh(new THREE.BoxGeometry(0.015, 0.006, 0.20), crossMat);
  cross.position.y = 0.004;
  cross.add(crossA, crossB);
  reticle.add(cross);

  buildTargetVisuals();
  addEventListener('resize', onResize);
}

function buildTargetVisuals() {
  targetGroup.clear();
  highlightRings = [];
  guideDashes = [];

  const redMaterial = new THREE.MeshBasicMaterial({
    color: 0xff334f,
    transparent: true,
    opacity: 0.92,
    blending: THREE.AdditiveBlending,
    depthTest: false,
    depthWrite: false,
    side: THREE.DoubleSide
  });

  highlightCore = new THREE.Group();
  const ringGeometry = new THREE.TorusGeometry(1, 0.055, 14, 80);
  const ringXY = new THREE.Mesh(ringGeometry, redMaterial.clone());
  const ringXZ = new THREE.Mesh(ringGeometry, redMaterial.clone());
  const ringYZ = new THREE.Mesh(ringGeometry, redMaterial.clone());
  ringXZ.rotation.x = Math.PI / 2;
  ringYZ.rotation.y = Math.PI / 2;
  highlightCore.add(ringXY, ringXZ, ringYZ);
  highlightRings.push(ringXY, ringXZ, ringYZ);
  targetGroup.add(highlightCore);

  const dashMaterial = new THREE.MeshBasicMaterial({
    color: 0x2cf58a,
    transparent: true,
    opacity: 0.9,
    depthTest: false,
    depthWrite: false
  });

  for (let i = 0; i < 10; i += 1) {
    const dash = new THREE.Mesh(new THREE.BoxGeometry(0.065, 0.09, 0.035), dashMaterial.clone());
    dash.visible = false;
    targetGroup.add(dash);
    guideDashes.push(dash);
  }

  const upArrow = new THREE.Mesh(
    new THREE.ConeGeometry(0.12, 0.25, 4),
    new THREE.MeshBasicMaterial({ color: 0x2cf58a, transparent: true, opacity: 0.95, depthTest: false, depthWrite: false })
  );
  upArrow.name = 'upArrow';
  targetGroup.add(upArrow);
  updateTargetVisuals();
}

function updateTargetVisuals() {
  const item = ITEMS[selectedKey];
  if (!item || !highlightCore) return;

  targetName.textContent = `${item.label} — ${item.detail}`;
  highlightCore.position.copy(item.position);
  highlightCore.scale.setScalar(item.radius);

  const usableHeight = Math.max(0.35, item.position.y - 0.12);
  const spacing = 0.18;
  guideDashes.forEach((dash, index) => {
    const y = 0.12 + index * spacing;
    dash.visible = y < usableHeight;
    dash.position.set(item.position.x, y, item.position.z);
  });

  const upArrow = targetGroup.getObjectByName('upArrow');
  upArrow.position.set(item.position.x, Math.max(0.28, item.position.y - 0.10), item.position.z);
  upArrow.visible = true;
}

function showToast(message, duration = 2200) {
  clearTimeout(lastToastTimer);
  toast.textContent = message;
  toast.classList.remove('hidden');
  lastToastTimer = setTimeout(() => toast.classList.add('hidden'), duration);
}

async function checkSupport() {
  initThree();
  if (!window.isSecureContext) {
    supportMessage.textContent = 'This page is not on HTTPS. Upload the folder to a secure host before starting AR.';
    startARButton.disabled = true;
    return false;
  }
  if (!navigator.xr) {
    supportMessage.textContent = 'WebXR is not exposed by this browser.';
    startARButton.disabled = true;
    return false;
  }
  try {
    const supported = await navigator.xr.isSessionSupported('immersive-ar');
    startARButton.disabled = !supported;
    supportMessage.textContent = supported
      ? 'Tracked AR is supported on this phone.'
      : 'This browser or phone does not report immersive AR support.';
    return supported;
  } catch (error) {
    startARButton.disabled = true;
    supportMessage.textContent = `AR support check failed: ${error.message}`;
    return false;
  }
}

async function startAR() {
  initThree();
  startARButton.disabled = true;
  supportMessage.textContent = 'Starting the camera and spatial tracking…';

  try {
    session = await navigator.xr.requestSession('immersive-ar', {
      requiredFeatures: ['hit-test', 'dom-overlay'],
      optionalFeatures: ['anchors'],
      domOverlay: { root: document.body }
    });

    session.addEventListener('end', onSessionEnded, { once: true });
    await renderer.xr.setSession(session);

    referenceSpace = await session.requestReferenceSpace('local');
    renderer.xr.setReferenceSpace(referenceSpace);
    viewerSpace = await session.requestReferenceSpace('viewer');
    hitTestSource = await session.requestHitTestSource({ space: viewerSpace });

    originPlaced = false;
    root.visible = false;
    reticle.visible = false;
    currentHit = null;
    placeEntranceButton.disabled = true;
    placementPanel.classList.remove('hidden');
    finderPanel.classList.add('hidden');
    landing.classList.add('hidden');
    unsupported.classList.add('hidden');
    overlay.classList.remove('hidden');
    statusEl.textContent = 'Move the phone slowly so it can detect the carpet.';

    renderer.setAnimationLoop(render);
  } catch (error) {
    console.error(error);
    startARButton.disabled = false;
    supportMessage.textContent = `Could not start AR: ${error.message}`;
    if (/secure|https/i.test(error.message)) {
      showToast('This app must be opened from an HTTPS address.');
    }
  }
}

function placeOrigin() {
  if (!reticle.visible || !currentHit) {
    showToast('Keep scanning until the green circle is visible.');
    return;
  }

  currentHitMatrix.decompose(root.position, tempQuaternion, tempScale);

  const xrCamera = renderer.xr.getCamera(camera);
  xrCamera.getWorldDirection(cameraForward);
  cameraForward.y = 0;
  if (cameraForward.lengthSq() < 0.001) cameraForward.set(0, 0, -1);
  cameraForward.normalize();

  root.rotation.set(0, Math.atan2(-cameraForward.x, -cameraForward.z), 0);
  root.scale.set(1, 1, 1);
  basePosition.copy(root.position);
  baseYaw = root.rotation.y;

  originPlaced = true;
  root.visible = true;
  reticle.visible = false;
  placementPanel.classList.add('hidden');
  finderPanel.classList.remove('hidden');
  statusEl.textContent = 'Entrance placed. Follow the green floor arrows.';
  lastRoutePosition.set(999, 0, 999);
  updateTargetVisuals();
  recomputeRoute(true);
  showToast('Room map placed. Walk normally and keep the camera pointed ahead.');
}

function resetPlacement() {
  originPlaced = false;
  root.visible = false;
  routeGroup.clear();
  placementPanel.classList.remove('hidden');
  finderPanel.classList.add('hidden');
  statusEl.textContent = 'Stand in the doorway, face the fireplace, and aim at the floor.';
  placeEntranceButton.disabled = !reticle.visible;
}

function nudgeMap(action) {
  if (!originPlaced) return;
  const movement = 0.05;
  const rotation = THREE.MathUtils.degToRad(2);

  switch (action) {
    case 'forward': root.translateZ(-movement); break;
    case 'back': root.translateZ(movement); break;
    case 'left': root.translateX(-movement); break;
    case 'right': root.translateX(movement); break;
    case 'rotateLeft': root.rotation.y += rotation; break;
    case 'rotateRight': root.rotation.y -= rotation; break;
    case 'reset':
      root.position.copy(basePosition);
      root.rotation.set(0, baseYaw, 0);
      break;
    default: return;
  }
  lastRoutePosition.set(999, 0, 999);
  recomputeRoute(true);
}

function makeArrow() {
  const group = new THREE.Group();
  const material = new THREE.MeshBasicMaterial({
    color: 0x2cf58a,
    transparent: true,
    opacity: 0.92,
    depthTest: false,
    depthWrite: false,
    side: THREE.DoubleSide
  });
  const shaft = new THREE.Mesh(new THREE.BoxGeometry(0.075, 0.018, 0.24), material);
  shaft.position.z = 0.07;
  const head = new THREE.Mesh(new THREE.ConeGeometry(0.13, 0.25, 3), material.clone());
  head.rotation.x = -Math.PI / 2;
  head.position.z = -0.14;
  group.add(shaft, head);
  group.userData.materials = [material, head.material];
  return group;
}

function rebuildArrowMeshes(path) {
  routeGroup.clear();
  if (path.length < 2) return;

  const spacing = 0.43;
  for (let i = 1; i < path.length; i += 1) {
    const a = path[i - 1];
    const b = path[i];
    const dx = b.x - a.x;
    const dz = b.y - a.y;
    const length = Math.hypot(dx, dz);
    if (length < 0.02) continue;
    const dir = new THREE.Vector3(dx / length, 0, dz / length);
    const quaternion = new THREE.Quaternion().setFromUnitVectors(new THREE.Vector3(0, 0, -1), dir);

    for (let d = 0.12; d < length; d += spacing) {
      const arrow = makeArrow();
      arrow.position.set(a.x + dir.x * d, 0.025, a.y + dir.z * d);
      arrow.quaternion.copy(quaternion);
      arrow.userData.phase = d + i * 0.37;
      routeGroup.add(arrow);
    }
  }
}

const CELL = 0.20;
const NX = Math.floor((room.maxX - room.minX) / CELL) + 1;
const NZ = Math.floor((room.maxZ - room.minZ) / CELL) + 1;

function pointBlocked(x, z, pad = 0.18) {
  if (x < room.minX || x > room.maxX || z < room.minZ || z > room.maxZ) return true;
  for (const obstacle of obstacles) {
    const angle = -(obstacle.rot || 0);
    const cos = Math.cos(angle);
    const sin = Math.sin(angle);
    const dx = x - obstacle.x;
    const dz = z - obstacle.z;
    const localX = dx * cos - dz * sin;
    const localZ = dx * sin + dz * cos;
    if (Math.abs(localX) < obstacle.w / 2 + pad && Math.abs(localZ) < obstacle.d / 2 + pad) return true;
  }
  return false;
}

function toCell(x, z) {
  return [
    Math.max(0, Math.min(NX - 1, Math.round((x - room.minX) / CELL))),
    Math.max(0, Math.min(NZ - 1, Math.round((z - room.minZ) / CELL)))
  ];
}

function toWorld(i, j) {
  return [room.minX + i * CELL, room.minZ + j * CELL];
}

function nearestOpen(i, j) {
  for (let radius = 0; radius < 12; radius += 1) {
    for (let dx = -radius; dx <= radius; dx += 1) {
      for (let dz = -radius; dz <= radius; dz += 1) {
        const ni = i + dx;
        const nj = j + dz;
        if (ni < 0 || nj < 0 || ni >= NX || nj >= NZ) continue;
        const [x, z] = toWorld(ni, nj);
        if (!pointBlocked(x, z, 0.08)) return [ni, nj];
      }
    }
  }
  return [i, j];
}

function aStar(startX, startZ, goalX, goalZ) {
  const [startI, startJ] = nearestOpen(...toCell(startX, startZ));
  const [goalI, goalJ] = nearestOpen(...toCell(goalX, goalZ));
  const start = startI + startJ * NX;
  const goal = goalI + goalJ * NX;
  const total = NX * NZ;
  const gScore = new Float32Array(total);
  const fScore = new Float32Array(total);
  const cameFrom = new Int32Array(total);
  const inOpen = new Uint8Array(total);
  gScore.fill(Infinity);
  fScore.fill(Infinity);
  cameFrom.fill(-1);
  const open = [start];
  inOpen[start] = 1;
  gScore[start] = 0;
  fScore[start] = Math.hypot(goalI - startI, goalJ - startJ);
  const directions = [
    [1, 0, 1], [-1, 0, 1], [0, 1, 1], [0, -1, 1],
    [1, 1, Math.SQRT2], [1, -1, Math.SQRT2], [-1, 1, Math.SQRT2], [-1, -1, Math.SQRT2]
  ];

  while (open.length) {
    let bestIndex = 0;
    for (let i = 1; i < open.length; i += 1) {
      if (fScore[open[i]] < fScore[open[bestIndex]]) bestIndex = i;
    }
    const current = open.splice(bestIndex, 1)[0];
    inOpen[current] = 0;
    if (current === goal) {
      const result = [];
      let node = current;
      while (node !== -1) {
        const i = node % NX;
        const j = Math.floor(node / NX);
        const [x, z] = toWorld(i, j);
        result.push(new THREE.Vector2(x, z));
        node = cameFrom[node];
      }
      return simplifyPath(result.reverse());
    }

    const currentI = current % NX;
    const currentJ = Math.floor(current / NX);
    for (const [di, dj, cost] of directions) {
      const ni = currentI + di;
      const nj = currentJ + dj;
      if (ni < 0 || nj < 0 || ni >= NX || nj >= NZ) continue;
      const [worldX, worldZ] = toWorld(ni, nj);
      if (pointBlocked(worldX, worldZ, 0.08)) continue;

      if (di && dj) {
        const [sideAX, sideAZ] = toWorld(currentI + di, currentJ);
        const [sideBX, sideBZ] = toWorld(currentI, currentJ + dj);
        if (pointBlocked(sideAX, sideAZ, 0.08) || pointBlocked(sideBX, sideBZ, 0.08)) continue;
      }

      const neighbour = ni + nj * NX;
      const tentative = gScore[current] + cost;
      if (tentative >= gScore[neighbour]) continue;
      cameFrom[neighbour] = current;
      gScore[neighbour] = tentative;
      fScore[neighbour] = tentative + Math.hypot(goalI - ni, goalJ - nj);
      if (!inOpen[neighbour]) {
        open.push(neighbour);
        inOpen[neighbour] = 1;
      }
    }
  }
  return [];
}

function simplifyPath(path) {
  if (path.length < 3) return path;
  const result = [path[0]];
  let previousDirection = '';
  for (let i = 1; i < path.length; i += 1) {
    const dx = Math.sign(path[i].x - path[i - 1].x);
    const dz = Math.sign(path[i].y - path[i - 1].y);
    const direction = `${dx},${dz}`;
    if (previousDirection && direction !== previousDirection) result.push(path[i - 1]);
    previousDirection = direction;
  }
  result.push(path[path.length - 1]);
  return result;
}

function pathLength(path) {
  let distance = 0;
  for (let i = 1; i < path.length; i += 1) distance += path[i].distanceTo(path[i - 1]);
  return distance;
}

function getUserLocalPosition() {
  const xrCamera = renderer.xr.getCamera(camera);
  xrCamera.getWorldPosition(worldCameraPosition);
  localCameraPosition.copy(worldCameraPosition);
  root.worldToLocal(localCameraPosition);
  localCameraPosition.y = 0;
  return localCameraPosition;
}

function recomputeRoute(force = false) {
  if (!originPlaced || !session) return;
  const user = getUserLocalPosition();
  const now = performance.now();
  if (!force && user.distanceTo(lastRoutePosition) < 0.32 && now - lastRouteTime < 1200) return;

  lastRoutePosition.copy(user);
  lastRouteTime = now;
  const item = ITEMS[selectedKey];
  routePath = aStar(user.x, user.z, item.approach.x, item.approach.y);
  rebuildArrowMeshes(routePath);
  const remaining = pathLength(routePath);
  if (force || startRouteDistance < remaining) startRouteDistance = Math.max(remaining, 0.1);
  updateRouteUI(remaining);
}

function updateRouteUI(remaining = pathLength(routePath)) {
  const item = ITEMS[selectedKey];
  const directToItem = Math.hypot(localCameraPosition.x - item.position.x, localCameraPosition.z - item.position.z);
  const distance = Math.min(remaining || directToItem, directToItem + 0.8);
  const progress = Math.max(0, Math.min(100, (1 - distance / Math.max(startRouteDistance, 0.1)) * 100));
  progressBar.style.width = `${progress}%`;

  if (distance < 0.45) {
    distanceText.textContent = 'You are at the item';
    statusEl.textContent = 'Look for the pulsing red highlight.';
  } else {
    distanceText.textContent = `${distance.toFixed(1)} m remaining`;
    statusEl.textContent = `Follow the green arrows to ${item.label}.`;
  }
}

function animateVisuals(time) {
  const pulse = 0.82 + Math.sin(time * 0.006) * 0.18;
  highlightRings.forEach((ring, index) => {
    ring.material.opacity = 0.58 + pulse * 0.34 - index * 0.06;
    const scale = 1 + index * 0.15 + Math.sin(time * 0.005 + index) * 0.06;
    ring.scale.setScalar(scale);
  });
  guideDashes.forEach((dash, index) => {
    dash.material.opacity = 0.45 + 0.45 * (0.5 + 0.5 * Math.sin(time * 0.008 - index * 0.7));
  });
  routeGroup.children.forEach((arrow) => {
    const opacity = 0.55 + 0.4 * (0.5 + 0.5 * Math.sin(time * 0.008 - arrow.userData.phase * 2.2));
    arrow.userData.materials?.forEach((material) => { material.opacity = opacity; });
  });
}

function render(time, frame) {
  if (!frame || !session) return;

  if (!originPlaced && hitTestSource) {
    const hits = frame.getHitTestResults(hitTestSource);
    if (hits.length > 0) {
      currentHit = hits[0];
      const pose = currentHit.getPose(referenceSpace);
      if (pose) {
        currentHitMatrix.fromArray(pose.transform.matrix);
        reticle.matrix.copy(currentHitMatrix);
        reticle.visible = true;
        placeEntranceButton.disabled = false;
        statusEl.textContent = 'Green circle found. Aim it at the doorway floor.';
      }
    } else {
      currentHit = null;
      reticle.visible = false;
      placeEntranceButton.disabled = true;
      statusEl.textContent = 'Move the phone slowly so it can detect the carpet.';
    }
  }

  if (originPlaced) {
    recomputeRoute(false);
    updateRouteUI();
    animateVisuals(time);
  }

  renderer.render(scene, camera);
}

async function endAR() {
  if (session) await session.end();
}

function onSessionEnded() {
  renderer.setAnimationLoop(null);
  hitTestSource?.cancel?.();
  hitTestSource = null;
  viewerSpace = null;
  referenceSpace = null;
  session = null;
  currentHit = null;
  originPlaced = false;
  root.visible = false;
  reticle.visible = false;
  routeGroup.clear();
  overlay.classList.add('hidden');
  landing.classList.remove('hidden');
  startARButton.disabled = false;
  supportMessage.textContent = 'AR session ended. You can start it again.';
}

function onResize() {
  if (!renderer) return;
  camera.aspect = innerWidth / innerHeight;
  camera.updateProjectionMatrix();
  renderer.setSize(innerWidth, innerHeight);
}

startARButton.addEventListener('click', startAR);
$('retrySupport').addEventListener('click', async () => {
  unsupported.classList.add('hidden');
  landing.classList.remove('hidden');
  await checkSupport();
});
$('showPlan').addEventListener('click', () => $('planPreview').classList.toggle('hidden'));
placeEntranceButton.addEventListener('click', placeOrigin);
$('recenter').addEventListener('click', resetPlacement);
$('endAR').addEventListener('click', endAR);
itemSelect.addEventListener('change', () => {
  selectedKey = itemSelect.value;
  updateTargetVisuals();
  lastRoutePosition.set(999, 0, 999);
  startRouteDistance = 0.1;
  recomputeRoute(true);
  showToast(`Finding ${ITEMS[selectedKey].label}.`);
});
document.querySelectorAll('[data-nudge]').forEach((button) => {
  button.addEventListener('click', () => nudgeMap(button.dataset.nudge));
});

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible' && !session) checkSupport();
});

checkSupport();
