"use strict";

const MAX_MEDIA_PREVIEW_BYTES = 100 * 1024 * 1024;
const MAX_VOICE_PREVIEW_BYTES = 25 * 1024 * 1024;

const state = {
  alerts: [],
  calls: [],
  broadcasts: [],
  voiceMessages: [],
  media: [],
  refreshVersion: 0,
  identityVersion: 0,
  devices: [],
  mapConfig: null,
  soundEnabled: false,
  audioContext: null,
  selectedAlertId: null,
  peerConnection: null,
  signalPollTimer: null,
  signalSequence: 0,
  accessProfile: null,
  securityAudit: [],
  mediaObjectUrl: null,
  mediaPreviewController: null,
  mediaPreviewVersion: 0,
  voiceObjectUrl: null,
  voicePreviewController: null,
  voicePreviewVersion: 0,
};

const elements = Object.fromEntries([
  "token", "actor-id", "actor-role", "refresh", "sound", "alerts", "calls", "broadcasts", "voice-messages", "media", "devices",
  "open-count", "critical-count", "device-count", "updated-at", "connection-state",
  "active-call-count", "helmet-map", "map-state", "map-empty", "map-attribution",
  "map-zoom-in", "map-zoom-out", "map-fit", "transition-dialog", "dialog-alert",
  "target-state", "transition-note", "dialog-error", "confirm-transition", "video-dialog",
  "video-title", "live-video", "video-state", "close-video",
  "access-mode", "access-profile", "access-directory", "security-audit",
  "media-device-filter", "media-kind-filter", "apply-media-filter", "media-dialog",
  "media-preview-title", "media-preview-image", "media-preview-video", "media-preview-state",
  "close-media-preview",
  "broadcast-compose", "broadcast-device", "broadcast-text", "broadcast-priority",
  "broadcast-language", "broadcast-validity", "send-broadcast", "broadcast-form-state",
  "voice-access-state", "voice-dialog", "voice-preview-title", "voice-preview-audio",
  "voice-preview-state", "close-voice-preview",
].map(id => [id, document.getElementById(id)]));

function headers() {
  const token = elements.token.value.trim();
  const actorId = state.accessProfile?.principalId || elements["actor-id"].value.trim();
  const actorRole = state.accessProfile?.role || elements["actor-role"].value;
  if (!token || !/^[A-Za-z0-9._:-]{1,128}$/.test(actorId)) throw new Error("令牌或操作员 ID 无效");
  return {
    "Authorization": `Bearer ${token}`,
    "Content-Type": "application/json",
    "X-Actor-Id": actorId,
    "X-Actor-Role": actorRole,
  };
}

async function loadAccessProfile(expectedRefreshVersion = null) {
  const token = elements.token.value.trim();
  if (!token) throw new Error("访问令牌不能为空");
  const requestProfile = async includeActor => {
    const requestHeaders = { "Authorization": `Bearer ${token}` };
    if (includeActor) {
      requestHeaders["X-Actor-Id"] = elements["actor-id"].value.trim();
      requestHeaders["X-Actor-Role"] = elements["actor-role"].value;
    }
    const response = await fetch("/v1/access-profile", { headers: requestHeaders });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
      const error = new Error(body.error || `HTTP ${response.status}`);
      error.status = response.status;
      throw error;
    }
    return body;
  };
  let profile;
  try {
    profile = await requestProfile(false);
  } catch (error) {
    if (error.status !== 403) throw error;
    profile = await requestProfile(true);
  }
  if (expectedRefreshVersion != null && expectedRefreshVersion !== state.refreshVersion) {
    const error = new Error("身份读取已取消");
    error.name = "AbortError";
    throw error;
  }
  state.accessProfile = profile;
  state.securityAudit = [];
  elements["actor-id"].value = profile.principalId;
  elements["actor-role"].value = profile.role;
  const productionIdentity = profile.mode === "PRODUCTION_PRINCIPAL";
  elements["actor-id"].readOnly = productionIdentity;
  elements["actor-role"].disabled = productionIdentity;
  renderAccess();
  return profile;
}

function resetAccessIdentity(clearOperationalState = false) {
  const previousProfile = state.accessProfile;
  state.refreshVersion += 1;
  state.identityVersion += 1;
  state.accessProfile = null;
  state.securityAudit = [];
  elements["actor-id"].readOnly = false;
  elements["actor-role"].disabled = false;
  closeLiveVideo();
  closeMediaPreview();
  closeVoicePreview();
  if (!clearOperationalState) {
    renderAccess();
    return;
  }
  state.alerts = [];
  state.calls = [];
  state.broadcasts = [];
  state.voiceMessages = [];
  state.media = [];
  state.devices = [];
  state.mapConfig = null;
  elements["broadcast-text"].value = "";
  elements["broadcast-form-state"].textContent = "连接后核对发送权限";
  if (previousProfile) {
    elements["actor-id"].value = "";
    elements["actor-role"].value = "DISPATCHER";
  }
  mapView.configure(null);
  render();
  showConnection("未连接", false);
}

async function request(path, options = {}) {
  if (!window.isSecureContext && !["127.0.0.1", "localhost"].includes(location.hostname)) {
    throw new Error("生产页面必须使用 HTTPS");
  }
  const response = await fetch(path, { ...options, headers: { ...headers(), ...(options.headers || {}) } });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.error || `HTTP ${response.status}`);
  return body;
}

async function refresh() {
  const refreshVersion = ++state.refreshVersion;
  elements.refresh.disabled = true;
  try {
    const profile = await loadAccessProfile(refreshVersion);
    if (refreshVersion !== state.refreshVersion) return;
    const auditRequest = profile.capabilities.includes("VIEW_SECURITY_AUDIT")
      ? request("/v1/security-audit?limit=50")
      : Promise.resolve({ events: [] });
    const voiceRequest = profile.capabilities.includes("PLAY_VOICE_MESSAGES")
      ? request("/v1/voice-messages?limit=100")
      : Promise.resolve({ messages: [] });
    const mediaQuery = new URLSearchParams({ limit: "100" });
    if (elements["media-device-filter"].value) mediaQuery.set("deviceId", elements["media-device-filter"].value);
    if (elements["media-kind-filter"].value) mediaQuery.set("kind", elements["media-kind-filter"].value);
    const [alertBody, callBody, broadcastBody, voiceBody, mediaBody, deviceBody, configuration, auditBody] = await Promise.all([
      request("/v1/alerts?limit=200"),
      request("/v1/calls?limit=100"),
      request("/v1/broadcasts?limit=100"),
      voiceRequest,
      request(`/v1/media?${mediaQuery}`),
      request("/v1/devices/overview?limit=500"),
      request("/v1/dashboard-config"),
      auditRequest,
    ]);
    if (refreshVersion !== state.refreshVersion) return;
    const priorAttention = new Set(state.alerts.filter(item => item.requiresAttention).map(item => item.alertId));
    const priorCalls = new Set(state.calls.filter(callRequiresAttention).map(item => item.callId));
    state.alerts = alertBody.alerts;
    state.calls = callBody.calls;
    state.broadcasts = broadcastBody.broadcasts;
    state.voiceMessages = voiceBody.messages;
    state.media = mediaBody.media;
    state.devices = deviceBody.devices;
    state.mapConfig = configuration.map;
    state.securityAudit = auditBody.events;
    mapView.configure(configuration.map);
    render();
    if (refreshVersion !== state.refreshVersion) return;
    showConnection("已连接", true);
    if (state.soundEnabled && (
      state.alerts.some(item => item.requiresAttention && !priorAttention.has(item.alertId)) ||
      state.calls.some(item => callRequiresAttention(item) && !priorCalls.has(item.callId))
    )) beep();
  } catch (error) {
    if (refreshVersion !== state.refreshVersion) return;
    showConnection(error.message, false);
  } finally {
    if (refreshVersion === state.refreshVersion) elements.refresh.disabled = false;
  }
}

function showConnection(message, connected) {
  elements["connection-state"].textContent = message;
  elements["connection-state"].style.borderColor = connected ? "#32d583" : "#f97066";
}

function render() {
  renderAccess();
  renderCalls();
  renderBroadcasts();
  renderVoiceMessages();
  renderMedia();
  renderDevices();
  elements.alerts.replaceChildren();
  if (!state.alerts.length) elements.alerts.append(emptyState("没有可显示的告警"));
  for (const alert of state.alerts) elements.alerts.append(alertCard(alert));
  elements["open-count"].textContent = state.alerts.filter(item => item.workflowState !== "CLOSED").length;
  elements["critical-count"].textContent = state.alerts.filter(item => item.severity === "CRITICAL" && item.workflowState !== "CLOSED").length;
  elements["active-call-count"].textContent = state.calls.filter(item => !callIsTerminal(item)).length;
  elements["device-count"].textContent = state.devices.length;
  elements["updated-at"].textContent = new Date().toLocaleString();
  mapView.setData(state.devices, state.alerts);
}

function renderAccess() {
  const profile = state.accessProfile;
  elements["access-profile"].replaceChildren();
  elements["access-directory"].replaceChildren();
  elements["security-audit"].replaceChildren();
  if (!profile) {
    elements["access-mode"].textContent = "连接后核对实际权限";
    elements["access-profile"].append(emptyState("尚未读取令牌对应的身份", true));
    elements["access-directory"].append(emptyState("仅本组织管理员可查看脱敏目录", true));
    elements["security-audit"].append(emptyState("仅本组织管理员可查看拒绝记录", true));
    return;
  }
  elements["access-mode"].textContent = profile.mode === "PRODUCTION_PRINCIPAL"
    ? "生产身份绑定"
    : "本地单令牌联调";
  const identity = document.createElement("dl");
  identity.className = "access-identity";
  addFact(identity, "主体", profile.principalId);
  addFact(identity, "角色", roleLabel(profile.role));
  addFact(identity, "组织", profile.organizationId || "本地联调未绑定");
  addFact(identity, "设备范围", deviceScopeLabel(profile.deviceScope));
  elements["access-profile"].append(identity);
  const capabilities = document.createElement("div");
  capabilities.className = "capabilities";
  for (const capability of profile.capabilities) {
    const badge = document.createElement("span");
    badge.textContent = capabilityLabel(capability);
    capabilities.append(badge);
  }
  elements["access-profile"].append(capabilities);

  if (!profile.directory) {
    elements["access-directory"].append(emptyState("当前角色不可查看权限目录", true));
  } else {
    const organization = document.createElement("p");
    organization.className = "directory-summary";
    organization.textContent = `${profile.directory.organizationId} · ${profile.directory.deviceIds.length} 台设备`;
    elements["access-directory"].append(organization);
    for (const principal of profile.directory.principals) {
      const item = document.createElement("article");
      item.className = "directory-principal";
      const heading = document.createElement("strong");
      heading.textContent = principal.principalId;
      const role = document.createElement("span");
      role.textContent = roleLabel(principal.role);
      const scope = document.createElement("small");
      scope.textContent = principal.configuredDeviceIds.length
        ? `指定设备：${principal.effectiveDeviceIds.join("、") || "无"}`
        : `组织范围：${principal.effectiveDeviceIds.join("、") || "无设备"}`;
      item.append(heading, role, scope);
      elements["access-directory"].append(item);
    }
  }

  if (!profile.capabilities.includes("VIEW_SECURITY_AUDIT")) {
    elements["security-audit"].append(emptyState("当前角色不可查看权限拒绝审计", true));
  } else if (!state.securityAudit.length) {
    elements["security-audit"].append(emptyState("本组织没有权限拒绝记录", true));
  } else {
    for (const event of state.securityAudit) {
      const item = document.createElement("article");
      item.className = "audit-event";
      const heading = document.createElement("strong");
      heading.textContent = `${event.statusCode} · ${event.resourcePath}`;
      const actor = document.createElement("span");
      actor.textContent = `${event.principalId} · ${roleLabel(event.principalRole)}`;
      const detail = document.createElement("small");
      detail.textContent = `${event.reason} · ${formatTime(event.occurredAtEpochMillis)}`;
      item.append(heading, actor, detail);
      elements["security-audit"].append(item);
    }
  }
}

function roleLabel(role) {
  return ({ VIEWER: "查看", DISPATCHER: "调度", SUPERVISOR: "主管", ADMIN: "管理员", DEVICE: "设备" })[role] || role;
}

function deviceScopeLabel(scope) {
  if (!scope || scope.kind === "ALL") return "全部设备";
  const devices = scope.deviceIds.join("、") || "无设备";
  return scope.kind === "ORGANIZATION" ? `本组织：${devices}` : `指定设备：${devices}`;
}

function capabilityLabel(value) {
  return ({
    READ_OPERATIONS: "查看运行数据",
    CONTROL_ALERTS: "处置告警",
    CONTROL_CALLS: "控制呼叫",
    CONTROL_BROADCASTS: "发送文字广播",
    CONNECT_LIVE_VIDEO: "连接实时视频",
    PLAY_VOICE_MESSAGES: "播放语音消息",
    VIEW_ACCESS_DIRECTORY: "查看权限目录",
    VIEW_SECURITY_AUDIT: "查看拒绝审计",
  })[value] || value;
}

function emptyState(message, compact = false) {
  const empty = document.createElement("div");
  empty.className = `empty${compact ? " compact" : ""}`;
  empty.textContent = message;
  return empty;
}

function renderCalls() {
  elements.calls.replaceChildren();
  if (!state.calls.length) {
    elements.calls.append(emptyState("没有呼叫记录", true));
    return;
  }
  for (const call of state.calls) elements.calls.append(callCard(call));
}

function callCard(call) {
  const article = document.createElement("article");
  article.className = `call ${call.state}`;
  const head = document.createElement("div");
  head.className = "call-head";
  const title = document.createElement("h3");
  title.textContent = `${call.mediaMode === "VIDEO_UPLINK" ? "视频" : "语音"} · ${call.deviceId}`;
  const badge = document.createElement("span");
  badge.className = "badge call-badge";
  badge.textContent = callStateLabel(call.state);
  head.append(title, badge);
  article.append(head);

  const meta = document.createElement("div");
  meta.className = "meta call-meta";
  addMeta(meta, "呼叫 ID", call.callId);
  addMeta(meta, "发起时间", formatTime(call.createdAtEpochMillis));
  addMeta(meta, "状态序号", String(call.stateSequence));
  addMeta(meta, "数据来源", call.simulated ? "模拟" : "设备上报");
  article.append(meta);

  if (elements["actor-role"].value !== "VIEWER" && !callIsTerminal(call)) {
    const actions = document.createElement("div");
    actions.className = "call-actions";
    if (["REQUESTED", "RINGING"].includes(call.state)) {
      actions.append(
        callActionButton("接听", call, "ACCEPTED", "OPERATOR_ACCEPTED"),
        callActionButton("拒绝", call, "REJECTED", "OPERATOR_REJECTED", true),
      );
    } else {
      actions.append(callActionButton("挂断", call, "ENDED", "OPERATOR_ENDED", true));
    }
    article.append(actions);
  }
  return article;
}

function callActionButton(label, call, target, reason, secondary = false) {
  const button = document.createElement("button");
  button.type = "button";
  button.textContent = label;
  if (secondary) button.className = "secondary";
  button.addEventListener("click", () => transitionCall(call, target, reason, button));
  return button;
}

async function transitionCall(call, target, reason, button) {
  button.disabled = true;
  try {
    await request(`/v1/calls/${encodeURIComponent(call.callId)}/transitions`, {
      method: "POST",
      body: JSON.stringify({
        state: target,
        actorId: elements["actor-id"].value.trim(),
        occurredAtEpochMillis: Date.now(),
        reason,
      }),
    });
    await refresh();
  } catch (error) {
    showConnection(error.message, false);
  } finally {
    button.disabled = false;
  }
}

function callRequiresAttention(call) {
  return ["REQUESTED", "RINGING"].includes(call.state);
}

function callIsTerminal(call) {
  return ["REJECTED", "ENDED", "FAILED"].includes(call.state);
}

function callStateLabel(value) {
  return ({
    REQUESTED: "待接听", RINGING: "振铃中", ACCEPTED: "已接听", CONNECTING: "连接中",
    CONNECTED: "通话中", REJECTED: "已拒绝", ENDED: "已结束", FAILED: "失败",
  })[value] || value;
}

function renderBroadcasts() {
  const selectedDevice = elements["broadcast-device"].value;
  elements["broadcast-device"].replaceChildren();
  for (const device of state.devices) {
    const option = document.createElement("option");
    option.value = device.deviceId;
    option.textContent = device.personId ? `${device.deviceId} · ${device.personId}` : device.deviceId;
    elements["broadcast-device"].append(option);
  }
  if ([...elements["broadcast-device"].options].some(option => option.value === selectedDevice)) {
    elements["broadcast-device"].value = selectedDevice;
  }
  const canControl = state.accessProfile?.capabilities.includes("CONTROL_BROADCASTS") === true;
  elements["broadcast-compose"].disabled = !canControl || !state.devices.length;
  if (!canControl) {
    elements["broadcast-form-state"].textContent = state.accessProfile
      ? "当前角色只读，不能发送广播"
      : "连接后核对发送权限";
  } else if (!state.devices.length) {
    elements["broadcast-form-state"].textContent = "当前身份没有可选择的设备";
  } else if (["连接后核对发送权限", "当前角色只读，不能发送广播", "当前身份没有可选择的设备"].includes(
    elements["broadcast-form-state"].textContent,
  )) {
    elements["broadcast-form-state"].textContent = "发送后等待设备返回接收、播放或失败状态";
  }

  elements.broadcasts.replaceChildren();
  if (!state.broadcasts.length) {
    elements.broadcasts.append(emptyState("没有文字广播记录", true));
    return;
  }
  for (const broadcast of state.broadcasts) elements.broadcasts.append(broadcastCard(broadcast));
}

function broadcastCard(broadcast) {
  const article = document.createElement("article");
  article.className = `broadcast priority-${broadcast.priority}`;
  const head = document.createElement("div");
  head.className = "broadcast-head";
  const title = document.createElement("h3");
  title.textContent = broadcast.deviceId;
  const badge = document.createElement("span");
  badge.className = "badge broadcast-badge";
  badge.textContent = broadcastStatusLabel(broadcast);
  head.append(title, badge);
  article.append(head);

  const content = document.createElement("p");
  content.className = "broadcast-text";
  content.textContent = broadcast.text;
  article.append(content);
  const meta = document.createElement("div");
  meta.className = "meta broadcast-meta";
  addMeta(meta, "广播 ID", broadcast.broadcastId);
  addMeta(meta, "发送时间", formatTime(broadcast.createdAtEpochMillis));
  addMeta(meta, "优先级", broadcastPriorityLabel(broadcast.priority));
  addMeta(meta, "语言", broadcast.language);
  addMeta(meta, "有效期", broadcast.expiresAtEpochMillis ? formatTime(broadcast.expiresAtEpochMillis) : "未设置");
  addMeta(meta, "最后更新", broadcast.updatedAtEpochMillis ? formatTime(broadcast.updatedAtEpochMillis) : "等待设备回执");
  article.append(meta);

  const receipts = document.createElement("ol");
  receipts.className = "broadcast-receipts";
  if (!broadcast.receipts.length) {
    const waiting = document.createElement("li");
    waiting.textContent = "等待设备接收";
    receipts.append(waiting);
  } else {
    for (const receipt of broadcast.receipts) {
      const item = document.createElement("li");
      const stateLabel = broadcastStateLabel(receipt.state);
      item.textContent = `${stateLabel} · ${formatTime(receipt.occurredAtEpochMillis)}${receipt.error ? ` · ${receipt.error}` : ""}`;
      receipts.append(item);
    }
  }
  article.append(receipts);
  return article;
}

function broadcastStatusLabel(broadcast) {
  if (broadcast.lastState) return broadcastStateLabel(broadcast.lastState);
  if (broadcast.expiresAtEpochMillis && broadcast.expiresAtEpochMillis <= Date.now()) return "已过期，未回执";
  return "等待接收";
}

function broadcastStateLabel(value) {
  return ({
    RECEIVED: "已接收", PLAYING: "播放中", PLAYED: "已播放", FAILED: "播放失败", EXPIRED: "已过期",
  })[value] || value;
}

function broadcastPriorityLabel(value) {
  if (value >= 10) return "紧急";
  if (value <= 0) return "低";
  return "普通";
}

async function sendBroadcast() {
  const deviceId = elements["broadcast-device"].value;
  const message = elements["broadcast-text"].value.trim();
  const language = elements["broadcast-language"].value.trim();
  if (!deviceId) {
    elements["broadcast-form-state"].textContent = "请选择目标设备";
    return;
  }
  if (!message || message.length > 2000) {
    elements["broadcast-form-state"].textContent = "文字内容长度必须为 1 至 2000 个字符";
    return;
  }
  if (!/^[A-Za-z0-9._:-]{1,128}$/.test(language)) {
    elements["broadcast-form-state"].textContent = "语言标识格式无效";
    return;
  }
  const createdAtEpochMillis = Date.now();
  const validityMinutes = Number(elements["broadcast-validity"].value);
  elements["send-broadcast"].disabled = true;
  elements["broadcast-form-state"].textContent = "正在提交广播";
  try {
    const response = await request("/v1/broadcasts", {
      method: "POST",
      body: JSON.stringify({
        broadcastId: `broadcast-${crypto.randomUUID()}`,
        deviceId,
        text: message,
        language,
        priority: Number(elements["broadcast-priority"].value),
        expiresAtEpochMillis: validityMinutes ? createdAtEpochMillis + validityMinutes * 60_000 : null,
        createdAtEpochMillis,
      }),
    });
    elements["broadcast-text"].value = "";
    await refresh();
    elements["broadcast-form-state"].textContent = `已发送 ${response.broadcastId}，等待设备回执`;
  } catch (error) {
    elements["broadcast-form-state"].textContent = `发送失败：${error.message}`;
  } finally {
    elements["send-broadcast"].disabled = false;
  }
}

function renderVoiceMessages() {
  const canPlay = state.accessProfile?.capabilities.includes("PLAY_VOICE_MESSAGES") === true;
  elements["voice-access-state"].textContent = canPlay
    ? "授权角色 · 播放操作写入审计"
    : state.accessProfile ? "当前角色不可检索语音消息" : "连接后核对播放权限";
  elements["voice-messages"].replaceChildren();
  if (!canPlay) {
    elements["voice-messages"].append(emptyState("当前身份没有语音消息播放权限", true));
    return;
  }
  if (!state.voiceMessages.length) {
    elements["voice-messages"].append(emptyState("没有当前角色可播放的语音消息", true));
    return;
  }
  for (const message of state.voiceMessages) elements["voice-messages"].append(voiceMessageCard(message));
}

function voiceMessageCard(message) {
  const article = document.createElement("article");
  article.className = "voice-message";
  const head = document.createElement("div");
  head.className = "voice-head";
  const title = document.createElement("h3");
  title.textContent = `${message.deviceId} · ${formatDuration(message.durationMillis)}`;
  const badge = document.createElement("span");
  badge.className = "badge voice-badge";
  badge.textContent = message.senderRole === "DEVICE" ? "设备消息" : "管理端消息";
  head.append(title, badge);
  article.append(head);
  const meta = document.createElement("div");
  meta.className = "meta voice-meta";
  addMeta(meta, "消息 ID", message.messageId);
  addMeta(meta, "时间", formatTime(message.createdAtEpochMillis));
  addMeta(meta, "发送者", `${message.senderId} · ${roleLabel(message.senderRole)}`);
  addMeta(meta, "大小", formatBytes(message.byteSize));
  addMeta(meta, "呼叫", message.callId || "无");
  addMeta(meta, "关联事件", message.relatedEventId || "无");
  addMeta(meta, "格式", message.mimeType);
  addMeta(meta, "允许角色", message.allowedRoles.map(roleLabel).join("、"));
  article.append(meta);
  const actions = document.createElement("div");
  actions.className = "voice-actions";
  if (message.byteSize <= MAX_VOICE_PREVIEW_BYTES) {
    const play = document.createElement("button");
    play.type = "button";
    play.textContent = "打开播放器";
    play.addEventListener("click", () => playVoiceMessage(message, play));
    actions.append(play);
  } else {
    const note = document.createElement("span");
    note.className = "voice-note";
    note.textContent = "超过 25 MiB，请使用受控客户端下载";
    actions.append(note);
  }
  article.append(actions);
  return article;
}

async function readAuthenticatedBlob(path, metadata, maximumBytes, signal) {
  const response = await fetch(path, { headers: headers(), signal });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.error || `HTTP ${response.status}`);
  }
  const lengthHeader = response.headers.get("Content-Length");
  const declaredLength = lengthHeader == null || !/^[0-9]+$/.test(lengthHeader)
    ? Number.NaN
    : Number(lengthHeader);
  if (!Number.isSafeInteger(declaredLength) || declaredLength !== metadata.byteSize) {
    throw new Error("正文长度与元数据不一致");
  }
  if (declaredLength > maximumBytes) throw new Error("正文超过浏览器预览上限");
  const contentType = (response.headers.get("Content-Type") || "").split(";", 1)[0];
  if (contentType !== metadata.mimeType) throw new Error("正文类型与元数据不一致");
  if ((response.headers.get("ETag") || "").replaceAll('"', "") !== metadata.sha256) {
    throw new Error("正文摘要标识与元数据不一致");
  }
  const reader = response.body?.getReader();
  if (!reader) throw new Error("浏览器无法流式读取正文");
  const chunks = [];
  let received = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    received += value.byteLength;
    if (received > declaredLength || received > maximumBytes) {
      await reader.cancel();
      throw new Error("正文超过声明长度或预览上限");
    }
    chunks.push(value);
  }
  if (received !== declaredLength) throw new Error("正文未完整读取");
  return { blob: new Blob(chunks, { type: contentType }), received };
}

async function playVoiceMessage(message, button) {
  button.disabled = true;
  closeVoicePreview();
  closeMediaPreview();
  const previewVersion = state.voicePreviewVersion;
  const controller = new AbortController();
  state.voicePreviewController = controller;
  elements["voice-preview-title"].textContent = `语音消息 · ${message.deviceId}`;
  elements["voice-preview-state"].textContent = "正在校验并读取语音；播放请求将写入审计";
  elements["voice-dialog"].showModal();
  try {
    const expectedPath = `/v1/voice-messages/${encodeURIComponent(message.messageId)}/content`;
    const content = await readAuthenticatedBlob(
      expectedPath, message, MAX_VOICE_PREVIEW_BYTES, controller.signal,
    );
    if (state.voicePreviewVersion !== previewVersion) return;
    state.voicePreviewController = null;
    state.voiceObjectUrl = URL.createObjectURL(content.blob);
    elements["voice-preview-audio"].src = state.voiceObjectUrl;
    elements["voice-preview-audio"].load();
    elements["voice-preview-state"].textContent = `${formatBytes(content.received)} · 已核对长度、类型和摘要标识，请点击播放`;
  } catch (error) {
    if (state.voicePreviewVersion === previewVersion) {
      clearVoicePreview();
      elements["voice-preview-state"].textContent = `读取失败：${error.name === "AbortError" ? "读取已取消" : error.message}`;
    }
  } finally {
    button.disabled = false;
  }
}

function clearVoicePreview() {
  state.voicePreviewVersion += 1;
  if (state.voicePreviewController) state.voicePreviewController.abort();
  state.voicePreviewController = null;
  if (state.voiceObjectUrl) URL.revokeObjectURL(state.voiceObjectUrl);
  state.voiceObjectUrl = null;
  elements["voice-preview-audio"].pause();
  elements["voice-preview-audio"].removeAttribute("src");
  elements["voice-preview-audio"].load();
}

function closeVoicePreview() {
  clearVoicePreview();
  if (elements["voice-dialog"].open) elements["voice-dialog"].close();
}

function renderMedia() {
  const selectedDevice = elements["media-device-filter"].value;
  elements["media-device-filter"].replaceChildren();
  const allDevices = document.createElement("option");
  allDevices.value = "";
  allDevices.textContent = "全部可访问设备";
  elements["media-device-filter"].append(allDevices);
  for (const device of state.devices) {
    const option = document.createElement("option");
    option.value = device.deviceId;
    option.textContent = device.personId ? `${device.deviceId} · ${device.personId}` : device.deviceId;
    elements["media-device-filter"].append(option);
  }
  elements["media-device-filter"].value = [...elements["media-device-filter"].options]
    .some(option => option.value === selectedDevice) ? selectedDevice : "";

  elements.media.replaceChildren();
  if (!state.media.length) {
    elements.media.append(emptyState("当前筛选条件下没有照片或视频", true));
    return;
  }
  for (const media of state.media) elements.media.append(mediaCard(media));
}

function mediaCard(media) {
  const article = document.createElement("article");
  article.className = "media-archive";
  const head = document.createElement("div");
  head.className = "media-head";
  const title = document.createElement("h3");
  title.textContent = `${media.kind === "PHOTO" ? "照片" : "视频"} · ${media.deviceId}`;
  const badge = document.createElement("span");
  badge.className = "badge media-badge";
  badge.textContent = media.status === "COMPLETED" ? "已归档" : media.status;
  head.append(title, badge);
  article.append(head);

  const meta = document.createElement("div");
  meta.className = "meta media-meta";
  addMeta(meta, "媒体 ID", media.mediaId);
  addMeta(meta, "拍摄时间", formatTime(media.createdAtEpochMillis));
  addMeta(meta, "人员", media.personId || "未绑定");
  addMeta(meta, "大小", formatBytes(media.byteSize));
  addMeta(meta, "尺寸", `${media.width} × ${media.height}`);
  addMeta(meta, "时长", media.durationMillis == null ? "不适用" : formatDuration(media.durationMillis));
  addMeta(meta, "关联事件", media.relatedEventId || "无");
  addMeta(meta, "位置", locationLabel(media.location));
  article.append(meta);

  const actions = document.createElement("div");
  actions.className = "media-actions";
  if (media.byteSize <= MAX_MEDIA_PREVIEW_BYTES) {
    const preview = document.createElement("button");
    preview.type = "button";
    preview.textContent = "预览";
    preview.addEventListener("click", () => previewMedia(media, preview));
    actions.append(preview);
  } else {
    const note = document.createElement("span");
    note.className = "media-note";
    note.textContent = "超过 100 MiB，请使用受控客户端下载";
    actions.append(note);
  }
  article.append(actions);
  return article;
}

async function previewMedia(media, button) {
  button.disabled = true;
  closeMediaPreview();
  closeVoicePreview();
  const previewVersion = state.mediaPreviewVersion;
  const controller = new AbortController();
  state.mediaPreviewController = controller;
  elements["media-preview-title"].textContent = `${media.kind === "PHOTO" ? "照片" : "视频"} · ${media.deviceId}`;
  elements["media-preview-state"].textContent = "正在校验并读取归档内容";
  elements["media-dialog"].showModal();
  try {
    const expectedPath = `/v1/media/${encodeURIComponent(media.mediaId)}/content`;
    if (media.contentPath !== expectedPath) throw new Error("归档内容路径无效");
    const content = await readAuthenticatedBlob(
      expectedPath, media, MAX_MEDIA_PREVIEW_BYTES, controller.signal,
    );
    if (state.mediaPreviewVersion !== previewVersion) return;
    state.mediaPreviewController = null;
    state.mediaObjectUrl = URL.createObjectURL(content.blob);
    const target = media.kind === "PHOTO" ? elements["media-preview-image"] : elements["media-preview-video"];
    target.src = state.mediaObjectUrl;
    target.hidden = false;
    elements["media-preview-state"].textContent = `${formatBytes(content.received)} · 已完成长度、类型和摘要标识核对`;
  } catch (error) {
    if (state.mediaPreviewVersion === previewVersion) {
      clearMediaPreview();
      elements["media-preview-state"].textContent = `读取失败：${error.name === "AbortError" ? "读取已取消" : error.message}`;
    }
  } finally {
    button.disabled = false;
  }
}

function clearMediaPreview() {
  state.mediaPreviewVersion += 1;
  if (state.mediaPreviewController) state.mediaPreviewController.abort();
  state.mediaPreviewController = null;
  if (state.mediaObjectUrl) URL.revokeObjectURL(state.mediaObjectUrl);
  state.mediaObjectUrl = null;
  elements["media-preview-video"].pause();
  for (const target of [elements["media-preview-image"], elements["media-preview-video"]]) {
    target.removeAttribute("src");
    target.hidden = true;
  }
  elements["media-preview-video"].load();
}

function closeMediaPreview() {
  clearMediaPreview();
  if (elements["media-dialog"].open) elements["media-dialog"].close();
}

function renderDevices() {
  elements.devices.replaceChildren();
  if (!state.devices.length) {
    elements.devices.append(emptyState("没有设备状态", true));
    return;
  }
  for (const device of state.devices) elements.devices.append(deviceCard(device));
}

function deviceCard(device) {
  const article = document.createElement("article");
  article.className = `device${device.activeAlertCount ? " has-alert" : ""}`;
  article.tabIndex = 0;
  const head = document.createElement("div");
  head.className = "device-head";
  const title = document.createElement("h3");
  title.textContent = device.deviceId;
  const badge = document.createElement("span");
  badge.className = "badge device-badge";
  badge.textContent = device.activeAlertCount ? `${device.activeAlertCount} 个活动告警` : "状态正常";
  head.append(title, badge);
  article.append(head);

  const stateLine = document.createElement("p");
  stateLine.className = "device-state-line";
  stateLine.textContent = [device.operationalState || "状态未上报", device.networkState || "网络未上报"].join(" · ");
  article.append(stateLine);

  const facts = document.createElement("dl");
  facts.className = "device-facts";
  addFact(facts, "人员", device.personId || "未绑定");
  addFact(facts, "服务器联系", device.lastContactAtEpochMillis ? formatAge(device.lastContactAtEpochMillis) : "无状态上报");
  addFact(facts, "设备事件", device.lastSeenAtEpochMillis ? formatAge(device.lastSeenAtEpochMillis) : "未上报");
  addFact(facts, "设备时钟", clockOffsetLabel(device.clock));
  addFact(facts, "位置", locationLabel(device.location));
  addFact(facts, "电池", batteryLabel(device.battery));
  addFact(facts, "摄像头", device.cameraAvailable == null ? "未上报" : device.cameraAvailable ? "可用" : "不可用");
  addFact(facts, "硬件", device.hardwareMode || "未上报");
  addFact(facts, "RTK", rtkLabel(device.rtk));
  addFact(facts, "本地对讲", localIntercomLabel(device.localIntercom));
  addFact(facts, "数据来源", device.simulated == null ? "未上报" : device.simulated ? "模拟" : "真实设备");
  if (device.latestMedia) addFact(facts, "最新媒体", `${device.latestMedia.kind} · ${formatAge(device.latestMedia.createdAtEpochMillis)}`);
  article.append(facts);

  const actions = document.createElement("div");
  actions.className = "device-actions";
  if (device.location.latitude != null) {
    const locate = document.createElement("button");
    locate.type = "button";
    locate.className = "secondary";
    locate.textContent = "定位";
    locate.addEventListener("click", event => {
      event.stopPropagation();
      mapView.focus(device.location.latitude, device.location.longitude, Math.max(mapView.zoom, 16));
    });
    actions.append(locate);
  }
  if (device.liveVideo?.hasOffer && elements["actor-role"].value !== "VIEWER") {
    const video = document.createElement("button");
    video.type = "button";
    video.textContent = "连接实时视频";
    video.addEventListener("click", event => {
      event.stopPropagation();
      connectLiveVideo(device, video);
    });
    actions.append(video);
  } else if (device.liveVideo) {
    const waiting = document.createElement("span");
    waiting.className = "device-note";
    waiting.textContent = device.liveVideo.hasOffer ? "只读角色不可建立视频" : "等待设备视频信令";
    actions.append(waiting);
  }
  if (actions.childNodes.length) article.append(actions);
  const focus = () => {
    if (device.location.latitude != null) mapView.focus(device.location.latitude, device.location.longitude, mapView.zoom);
  };
  article.addEventListener("click", focus);
  article.addEventListener("keydown", event => {
    if (event.key === "Enter" || event.key === " ") focus();
  });
  return article;
}

function addFact(container, label, value) {
  const term = document.createElement("dt");
  const detail = document.createElement("dd");
  term.textContent = label;
  detail.textContent = value;
  container.append(term, detail);
}

function locationLabel(location) {
  if (!location || location.latitude == null) return "无可信定位";
  const accuracy = location.horizontalAccuracyMeters == null ? "" : ` · ±${Math.round(location.horizontalAccuracyMeters)} m`;
  return `${location.latitude.toFixed(5)}, ${location.longitude.toFixed(5)}${accuracy}`;
}

function batteryLabel(battery) {
  if (!battery?.reported) return "未上报";
  if (!battery.present) return "设备无电池";
  const voltage = battery.voltageMillivolts == null ? "电压未知" : `${(battery.voltageMillivolts / 1000).toFixed(2)} V`;
  return `${battery.percent}% · ${voltage}`;
}

function rtkLabel(rtk) {
  if (!rtk?.reported) return "未上报";
  const quality = rtk.lastFixQuality || "NO_FIX";
  return `${rtk.state || "UNKNOWN"} · ${quality} · ${rtk.correctionFrames || 0} 帧`;
}

function localIntercomLabel(intercom) {
  if (!intercom?.reported) return "未上报";
  const metrics = [];
  if (intercom.peerCount != null) metrics.push(`${intercom.peerCount} 个节点`);
  if (intercom.rssiDbm != null) metrics.push(`${intercom.rssiDbm} dBm`);
  if (intercom.packetLossPermille != null) metrics.push(`丢包 ${(intercom.packetLossPermille / 10).toFixed(1)}%`);
  if (intercom.oneWayLatencyMillis != null) metrics.push(`${intercom.oneWayLatencyMillis} ms`);
  return `${intercom.state || "UNKNOWN"}${metrics.length ? ` · ${metrics.join(" · ")}` : ""}`;
}

function clockOffsetLabel(clock) {
  if (!clock?.reported || clock.observedOffsetMillis == null) return "未上报";
  const source = clock.source === "SERVER"
    ? "服务器校时"
    : clock.source === "GNSS"
      ? "卫星校时"
      : clock.source === "SYSTEM"
        ? "系统时钟未校时"
        : "时间来源未上报";
  const uncertainty = clock.synchronized && clock.uncertaintyMillis != null
    ? `，不确定度约 ${formatUncertainty(clock.uncertaintyMillis)}`
    : "";
  const offset = clock.observedOffsetMillis;
  if (Math.abs(offset) < 1000) return `${source}${uncertainty}；与服务器相差小于 1 秒（含传输等待）`;
  return `${source}${uncertainty}；设备时钟${offset > 0 ? "快" : "慢"} ${formatDuration(Math.abs(offset))}（含传输等待）`;
}

function formatUncertainty(milliseconds) {
  if (milliseconds < 1000) return `${Math.max(1, Math.ceil(milliseconds))} 毫秒`;
  return formatDuration(milliseconds);
}

function alertCard(alert) {
  const article = document.createElement("article");
  article.className = `alert ${alert.severity} ${alert.workflowState}`;
  const head = document.createElement("div");
  head.className = "alert-head";
  const title = document.createElement("h3");
  title.textContent = `${alert.type} · ${alert.deviceId}`;
  const badge = document.createElement("span");
  badge.className = "badge";
  badge.textContent = `${alert.severity} / ${alert.workflowState}`;
  head.append(title, badge);
  article.append(head);

  const meta = document.createElement("div");
  meta.className = "meta";
  addMeta(meta, "告警 ID", alert.alertId);
  addMeta(meta, "发生时间", formatTime(alert.occurredAtEpochMillis));
  addMeta(meta, "设备状态", alert.active ? "报警中" : "已解除");
  addMeta(meta, "本地动作位", String(alert.localActions));
  addMeta(meta, "配置版本", alert.configVersion == null ? "无" : String(alert.configVersion));
  addMeta(meta, "数据来源", alert.simulated ? "模拟" : "设备上报");
  article.append(meta);

  if (alert.location.latitude != null) {
    const locate = document.createElement("button");
    locate.type = "button";
    locate.className = "text-button";
    locate.textContent = `地图定位 ${alert.location.latitude.toFixed(6)}, ${alert.location.longitude.toFixed(6)}`;
    locate.addEventListener("click", () => mapView.focus(alert.location.latitude, alert.location.longitude, 17));
    article.append(locate);
  }
  if (alert.workflowState !== "CLOSED" && elements["actor-role"].value !== "VIEWER") {
    const actions = document.createElement("div");
    actions.className = "alert-actions";
    const spacer = document.createElement("span");
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = "更新处置";
    button.addEventListener("click", () => openTransition(alert));
    actions.append(spacer, button);
    article.append(actions);
  }
  return article;
}

function addMeta(container, label, value) {
  const item = document.createElement("span");
  item.textContent = `${label}：${value}`;
  container.append(item);
}

function formatTime(epochMillis) {
  return new Date(epochMillis).toLocaleString();
}

function formatAge(epochMillis) {
  const seconds = Math.max(0, Math.round((Date.now() - epochMillis) / 1000));
  if (seconds < 60) return `${seconds} 秒前`;
  if (seconds < 3600) return `${Math.round(seconds / 60)} 分钟前`;
  if (seconds < 86400) return `${Math.round(seconds / 3600)} 小时前`;
  return formatTime(epochMillis);
}

function formatDuration(milliseconds) {
  const seconds = Math.round(milliseconds / 1000);
  if (seconds < 60) return `${seconds} 秒`;
  if (seconds < 3600) return `${Math.round(seconds / 60)} 分钟`;
  if (seconds < 86400) return `${Math.round(seconds / 3600)} 小时`;
  return `${Math.round(seconds / 86400)} 天`;
}

function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GiB`;
}

class HelmetMap {
  constructor(root) {
    this.root = root;
    this.tileLayer = root.querySelector(".tile-layer");
    this.overlay = root.querySelector(".map-overlay");
    this.routes = root.querySelector(".routes");
    this.markers = root.querySelector(".map-markers");
    this.center = { latitude: 35, longitude: 105 };
    this.zoom = 4;
    this.minZoom = 2;
    this.maxZoom = 19;
    this.tileTemplate = null;
    this.devices = [];
    this.alerts = [];
    this.drag = null;
    this.bind();
    new ResizeObserver(() => this.render()).observe(root);
  }

  bind() {
    this.root.addEventListener("wheel", event => {
      event.preventDefault();
      this.setZoom(this.zoom + (event.deltaY < 0 ? 1 : -1));
    }, { passive: false });
    this.root.addEventListener("pointerdown", event => {
      if (event.target.closest("button")) return;
      this.drag = { x: event.clientX, y: event.clientY, center: this.project(this.center.latitude, this.center.longitude) };
      this.root.setPointerCapture(event.pointerId);
    });
    this.root.addEventListener("pointermove", event => {
      if (!this.drag) return;
      const point = { x: this.drag.center.x - (event.clientX - this.drag.x), y: this.drag.center.y - (event.clientY - this.drag.y) };
      this.center = this.unproject(point.x, point.y);
      this.render();
    });
    const stop = () => { this.drag = null; };
    this.root.addEventListener("pointerup", stop);
    this.root.addEventListener("pointercancel", stop);
    this.root.addEventListener("keydown", event => {
      const amount = event.shiftKey ? 160 : 64;
      if (["+", "="].includes(event.key)) this.setZoom(this.zoom + 1);
      else if (event.key === "-") this.setZoom(this.zoom - 1);
      else if (event.key === "ArrowLeft") this.pan(amount, 0);
      else if (event.key === "ArrowRight") this.pan(-amount, 0);
      else if (event.key === "ArrowUp") this.pan(0, amount);
      else if (event.key === "ArrowDown") this.pan(0, -amount);
      else return;
      event.preventDefault();
    });
  }

  configure(config) {
    this.tileTemplate = config?.configured ? config.tileUrlTemplate : null;
    this.minZoom = config?.minimumZoom ?? 2;
    this.maxZoom = config?.maximumZoom ?? 19;
    this.zoom = Math.min(this.maxZoom, Math.max(this.minZoom, this.zoom));
    elements["map-attribution"].textContent = config?.attribution || "尚未配置生产地图底图";
    this.render();
  }

  setData(devices, alerts) {
    this.devices = devices;
    this.alerts = alerts.filter(alert => alert.workflowState !== "CLOSED" && alert.location.latitude != null);
    elements["map-empty"].hidden = this.points().length > 0;
    this.render();
  }

  points() {
    return [
      ...this.devices.filter(device => device.location.latitude != null).map(device => device.location),
      ...this.alerts.map(alert => alert.location),
    ];
  }

  project(latitude, longitude, zoom = this.zoom) {
    const scale = 256 * 2 ** zoom;
    const lat = Math.max(-85.05112878, Math.min(85.05112878, latitude));
    const sin = Math.sin(lat * Math.PI / 180);
    return {
      x: (longitude + 180) / 360 * scale,
      y: (0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) * scale,
    };
  }

  unproject(x, y, zoom = this.zoom) {
    const scale = 256 * 2 ** zoom;
    const boundedY = Math.max(0, Math.min(scale, y));
    const longitude = ((x % scale + scale) % scale) / scale * 360 - 180;
    const n = Math.PI - 2 * Math.PI * boundedY / scale;
    return { latitude: 180 / Math.PI * Math.atan(Math.sinh(n)), longitude };
  }

  screen(latitude, longitude) {
    const center = this.project(this.center.latitude, this.center.longitude);
    const point = this.project(latitude, longitude);
    const scale = 256 * 2 ** this.zoom;
    let deltaX = point.x - center.x;
    if (deltaX > scale / 2) deltaX -= scale;
    if (deltaX < -scale / 2) deltaX += scale;
    return { x: this.root.clientWidth / 2 + deltaX, y: this.root.clientHeight / 2 + point.y - center.y };
  }

  setZoom(zoom) {
    this.zoom = Math.min(this.maxZoom, Math.max(this.minZoom, Math.round(zoom)));
    this.render();
  }

  pan(deltaX, deltaY) {
    const center = this.project(this.center.latitude, this.center.longitude);
    this.center = this.unproject(center.x - deltaX, center.y - deltaY);
    this.render();
  }

  focus(latitude, longitude, zoom) {
    this.center = { latitude, longitude };
    this.setZoom(zoom);
    this.root.focus({ preventScroll: true });
  }

  fit() {
    const points = this.points();
    if (!points.length) return;
    const width = Math.max(100, this.root.clientWidth - 100);
    const height = Math.max(100, this.root.clientHeight - 100);
    let selected = this.minZoom;
    let bounds = null;
    for (let zoom = this.maxZoom; zoom >= this.minZoom; zoom -= 1) {
      const projected = points.map(point => this.project(point.latitude, point.longitude, zoom));
      const candidate = {
        minX: Math.min(...projected.map(point => point.x)), maxX: Math.max(...projected.map(point => point.x)),
        minY: Math.min(...projected.map(point => point.y)), maxY: Math.max(...projected.map(point => point.y)),
      };
      if (candidate.maxX - candidate.minX <= width && candidate.maxY - candidate.minY <= height) {
        selected = zoom;
        bounds = candidate;
        break;
      }
    }
    bounds ||= (() => {
      const projected = points.map(point => this.project(point.latitude, point.longitude, selected));
      return {
        minX: Math.min(...projected.map(point => point.x)), maxX: Math.max(...projected.map(point => point.x)),
        minY: Math.min(...projected.map(point => point.y)), maxY: Math.max(...projected.map(point => point.y)),
      };
    })();
    this.zoom = selected;
    this.center = this.unproject((bounds.minX + bounds.maxX) / 2, (bounds.minY + bounds.maxY) / 2, selected);
    this.render();
  }

  render() {
    if (!this.root.clientWidth || !this.root.clientHeight) return;
    this.renderTiles();
    this.renderRoutes();
    this.renderMarkers();
    elements["map-state"].textContent = `${this.tileTemplate ? "底图已配置" : "底图未配置，坐标层可用"} · Z${this.zoom}`;
  }

  renderTiles() {
    this.tileLayer.replaceChildren();
    if (!this.tileTemplate) return;
    const center = this.project(this.center.latitude, this.center.longitude);
    const left = center.x - this.root.clientWidth / 2;
    const top = center.y - this.root.clientHeight / 2;
    const startX = Math.floor(left / 256);
    const endX = Math.floor((left + this.root.clientWidth) / 256);
    const startY = Math.max(0, Math.floor(top / 256));
    const count = 2 ** this.zoom;
    const endY = Math.min(count - 1, Math.floor((top + this.root.clientHeight) / 256));
    for (let y = startY; y <= endY; y += 1) {
      for (let x = startX; x <= endX; x += 1) {
        const wrappedX = (x % count + count) % count;
        const image = document.createElement("img");
        image.alt = "";
        image.decoding = "async";
        image.referrerPolicy = "strict-origin-when-cross-origin";
        image.src = this.tileTemplate.replaceAll("{z}", this.zoom).replaceAll("{x}", wrappedX).replaceAll("{y}", y);
        image.style.transform = `translate(${Math.round(x * 256 - left)}px, ${Math.round(y * 256 - top)}px)`;
        this.tileLayer.append(image);
      }
    }
  }

  renderRoutes() {
    this.routes.replaceChildren();
    this.overlay.setAttribute("viewBox", `0 0 ${this.root.clientWidth} ${this.root.clientHeight}`);
    for (const device of this.devices) {
      const points = device.track
        .filter(point => Number.isFinite(point.latitude) && Number.isFinite(point.longitude))
        .map(point => this.screen(point.latitude, point.longitude));
      if (points.length < 2) continue;
      const line = document.createElementNS("http://www.w3.org/2000/svg", "polyline");
      line.setAttribute("points", points.map(point => `${point.x.toFixed(1)},${point.y.toFixed(1)}`).join(" "));
      line.setAttribute("class", "device-route");
      this.routes.append(line);
    }
  }

  renderMarkers() {
    this.markers.replaceChildren();
    for (const device of this.devices.filter(item => item.location.latitude != null)) {
      const point = this.screen(device.location.latitude, device.location.longitude);
      const marker = document.createElement("button");
      marker.type = "button";
      marker.className = `map-marker device-marker${device.activeAlertCount ? " has-alert" : ""}`;
      marker.style.transform = `translate(${Math.round(point.x)}px, ${Math.round(point.y)}px)`;
      marker.textContent = device.deviceId;
      marker.title = `${device.deviceId} · ${locationLabel(device.location)}`;
      marker.addEventListener("click", () => this.focus(device.location.latitude, device.location.longitude, Math.max(this.zoom, 16)));
      this.markers.append(marker);
    }
    for (const alert of this.alerts) {
      const point = this.screen(alert.location.latitude, alert.location.longitude);
      const marker = document.createElement("button");
      marker.type = "button";
      marker.className = `map-marker alert-marker ${alert.severity}`;
      marker.style.transform = `translate(${Math.round(point.x)}px, ${Math.round(point.y)}px)`;
      marker.textContent = "!";
      marker.title = `${alert.type} · ${alert.deviceId}`;
      marker.addEventListener("click", () => this.focus(alert.location.latitude, alert.location.longitude, Math.max(this.zoom, 17)));
      this.markers.append(marker);
    }
  }
}

const mapView = new HelmetMap(elements["helmet-map"]);

function openTransition(alert) {
  state.selectedAlertId = alert.alertId;
  elements["dialog-alert"].textContent = `${alert.type} / ${alert.alertId}`;
  elements["target-state"].value = alert.workflowState === "OPEN" ? "ACKNOWLEDGED" : alert.workflowState === "ACKNOWLEDGED" ? "IN_PROGRESS" : "CLOSED";
  elements["transition-note"].value = "";
  elements["dialog-error"].textContent = "";
  elements["transition-dialog"].showModal();
}

async function submitTransition() {
  const target = elements["target-state"].value;
  const note = elements["transition-note"].value.trim();
  if (target === "CLOSED" && !note) {
    elements["dialog-error"].textContent = "关闭告警必须填写说明";
    return;
  }
  try {
    await request(`/v1/alerts/${encodeURIComponent(state.selectedAlertId)}/transitions`, {
      method: "POST",
      body: JSON.stringify({ state: target, note: note || null, occurredAtEpochMillis: Date.now() }),
    });
    elements["transition-dialog"].close();
    await refresh();
  } catch (error) {
    elements["dialog-error"].textContent = error.message;
  }
}

async function connectLiveVideo(device, button) {
  button.disabled = true;
  closeLiveVideo();
  const callId = device.liveVideo.callId;
  const actorId = elements["actor-id"].value.trim();
  elements["video-title"].textContent = `实时视频 · ${device.deviceId}`;
  elements["video-state"].textContent = "正在获取信令";
  elements["video-dialog"].showModal();
  try {
    const [ice, signalBody] = await Promise.all([
      request(`/v1/calls/${encodeURIComponent(callId)}/ice-config?requesterId=${encodeURIComponent(actorId)}`),
      request(`/v1/calls/${encodeURIComponent(callId)}/signals?afterSequence=0&limit=500`),
    ]);
    const offer = [...signalBody.signals].reverse().find(signal => signal.type === "OFFER" && signal.senderId !== actorId);
    if (!offer) throw new Error("设备尚未提交视频 OFFER");
    state.signalSequence = Math.max(0, ...signalBody.signals.map(signal => signal.sequence));
    const connection = new RTCPeerConnection({ iceServers: ice.iceServers });
    state.peerConnection = connection;
    connection.addEventListener("track", event => {
      elements["live-video"].srcObject = event.streams[0] || new MediaStream([event.track]);
      elements["video-state"].textContent = "视频已连接";
    });
    connection.addEventListener("connectionstatechange", () => {
      elements["video-state"].textContent = `连接状态：${connection.connectionState}`;
    });
    connection.addEventListener("icecandidate", event => {
      const payload = event.candidate ? {
        candidate: event.candidate.candidate,
        sdpMid: event.candidate.sdpMid,
        sdpMLineIndex: event.candidate.sdpMLineIndex,
      } : {};
      emitSignal(callId, event.candidate ? "ICE_CANDIDATE" : "ICE_COMPLETE", payload).catch(error => {
        elements["video-state"].textContent = error.message;
      });
    });
    await connection.setRemoteDescription({ type: "offer", sdp: offer.payload.sdp });
    for (const signal of signalBody.signals) await applyRemoteCandidate(connection, signal, actorId);
    const answer = await connection.createAnswer();
    await connection.setLocalDescription(answer);
    await emitSignal(callId, "ANSWER", { sdp: answer.sdp });
    elements["video-state"].textContent = "已应答，等待视频轨道";
    pollSignals(callId, actorId);
  } catch (error) {
    elements["video-state"].textContent = `连接失败：${error.message}`;
    if (state.peerConnection) {
      state.peerConnection.close();
      state.peerConnection = null;
    }
  } finally {
    button.disabled = false;
  }
}

async function emitSignal(callId, type, payload) {
  return request(`/v1/calls/${encodeURIComponent(callId)}/signals`, {
    method: "POST",
    body: JSON.stringify({
      signalId: crypto.randomUUID(),
      senderId: elements["actor-id"].value.trim(),
      type,
      payload,
      createdAtEpochMillis: Date.now(),
    }),
  });
}

async function applyRemoteCandidate(connection, signal, actorId) {
  if (signal.senderId === actorId || signal.type !== "ICE_CANDIDATE") return;
  await connection.addIceCandidate(new RTCIceCandidate(signal.payload));
}

function pollSignals(callId, actorId) {
  clearTimeout(state.signalPollTimer);
  state.signalPollTimer = setTimeout(async () => {
    try {
      const body = await request(`/v1/calls/${encodeURIComponent(callId)}/signals?afterSequence=${state.signalSequence}&limit=500`);
      for (const signal of body.signals) {
        state.signalSequence = Math.max(state.signalSequence, signal.sequence);
        if (state.peerConnection) await applyRemoteCandidate(state.peerConnection, signal, actorId);
      }
      if (state.peerConnection && !["closed", "failed"].includes(state.peerConnection.connectionState)) pollSignals(callId, actorId);
    } catch (error) {
      elements["video-state"].textContent = `信令轮询失败：${error.message}`;
    }
  }, 1500);
}

function closeLiveVideo() {
  clearTimeout(state.signalPollTimer);
  state.signalPollTimer = null;
  if (state.peerConnection) state.peerConnection.close();
  state.peerConnection = null;
  if (elements["live-video"].srcObject) {
    for (const track of elements["live-video"].srcObject.getTracks()) track.stop();
    elements["live-video"].srcObject = null;
  }
}

function beep() {
  const context = state.audioContext;
  if (!context) return;
  const oscillator = context.createOscillator();
  const gain = context.createGain();
  oscillator.frequency.value = 880;
  gain.gain.setValueAtTime(.0001, context.currentTime);
  gain.gain.exponentialRampToValueAtTime(.18, context.currentTime + .02);
  gain.gain.exponentialRampToValueAtTime(.0001, context.currentTime + .35);
  oscillator.connect(gain).connect(context.destination);
  oscillator.start();
  oscillator.stop(context.currentTime + .36);
}

elements.refresh.addEventListener("click", refresh);
elements["apply-media-filter"].addEventListener("click", refresh);
elements["send-broadcast"].addEventListener("click", sendBroadcast);
elements.token.addEventListener("input", () => resetAccessIdentity(true));
elements["actor-id"].addEventListener("input", () => {
  if (state.accessProfile?.mode === "DEVELOPMENT_SINGLE_TOKEN") resetAccessIdentity();
});
elements.sound.addEventListener("click", () => {
  state.audioContext ||= new AudioContext();
  state.audioContext.resume();
  state.soundEnabled = true;
  elements.sound.textContent = "声音已启用";
  beep();
});
elements["confirm-transition"].addEventListener("click", submitTransition);
elements["actor-role"].addEventListener("change", () => {
  if (state.accessProfile?.mode === "DEVELOPMENT_SINGLE_TOKEN") resetAccessIdentity();
  if (elements["actor-role"].value === "VIEWER") closeLiveVideo();
  render();
});
elements["map-zoom-in"].addEventListener("click", () => mapView.setZoom(mapView.zoom + 1));
elements["map-zoom-out"].addEventListener("click", () => mapView.setZoom(mapView.zoom - 1));
elements["map-fit"].addEventListener("click", () => mapView.fit());
elements["video-dialog"].addEventListener("close", closeLiveVideo);
elements["media-dialog"].addEventListener("close", clearMediaPreview);
elements["voice-dialog"].addEventListener("close", clearVoicePreview);
