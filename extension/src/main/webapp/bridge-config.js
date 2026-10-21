(function () {
  'use strict';
  const proxy = 'http://127.0.0.1:18080/runninghub/comfy';
  const $ = (id) => document.getElementById(id);
  const root = document.createElement('section');
  root.className = 'wide';
  root.id = 'video-bridge-config';
  root.style.cssText = 'border:1px solid #4561a5;border-radius:15px;padding:24px;margin-top:20px;background:#1b2231';
  root.innerHTML = [
    '<h2>6. 接管原版分镜视频生成（RunningHub 云端代理）</h2>',
    '<p>这一功能让原版分镜视频请求继续走它原有的 ComfyUI 通信接口，但实际由 RunningHub 云端生成。不会修改视频数据库或覆盖原版 WAR。</p>',
    '<p style="color:#f5c77d">启用后需要在原版「系统设置 → 视频生成接口」把「本地 ComfyUI」的 API 地址改成下方兼容地址并保存。原版下拉项名称仍叫 ComfyUI，但视频生成实际将走 RunningHub。图片生成接口请保持原来的地址。</p>',
    '<div style="display:flex;gap:12px;align-items:center;flex-wrap:wrap">',
    '<code id="rh-bridge-url" style="overflow-wrap:anywhere;padding:11px 13px;background:#0f1625;border-radius:7px">' + proxy + '</code>',
    '<button id="rh-bridge-copy" type="button" class="secondary">复制兼容地址</button>',
    '<button id="rh-bridge-refresh" type="button" class="secondary">读取本机配置</button>',
    '</div>',
    '<label for="rh-bridge-map" style="margin:19px 0 7px">RunningHub 工作流节点映射 JSON</label>',
    '<p>将原版分镜视频的参数来源 <code>source</code> 绑定到 RunningHub 工作流内的节点：<code>targetNodeId</code> 与 <code>targetField</code>。按照上方「读取工作流」中的实际节点 ID 修改示例。支持 prompt、globalPrompt、localPrompts、image1～image4、duration、seed、width、height、fps、node、constant。</p>',
    '<textarea id="rh-bridge-map" spellcheck="false" style="width:100%;min-height:170px">',
    '[\n  {"targetNodeId":"6","targetField":"text","source":"prompt"},\n  {"targetNodeId":"10","targetField":"image","source":"image1"}\n]',
    '</textarea>',
    '<p style="font-size:12px;color:#f0bb76">示例里的节点 6 和 10 只是占位符，必须替换为你真实 RunningHub 工作流节点，否则不要开启。需要多张图片时增加 image2、image3 等条目。没有参考图的纯文生视频工作流可删除图片映射。</p>',
    '<label style="display:flex;gap:9px;align-items:center;margin-top:14px">',
    '<input type="checkbox" id="rh-bridge-enable" style="width:auto"> 启用原系统云端视频代理（提交视频可能消耗 RunningHub 额度）</label>',
    '<div style="display:flex;gap:9px;flex-wrap:wrap">',
    '<button type="button" id="rh-bridge-save">保存节点映射与代理配置</button>',
    '<button type="button" class="secondary" id="rh-bridge-test">测试代理连接（不会提交付费任务）</button>',
    '</div>',
    '<p>以上保存动作会把 API Key 存在 <code>Tomcat/conf/runninghub-video.json</code>，供原版后台自动提交视频使用。密钥不会上传 GitHub，也不会返回给浏览器。请保护本机 Tomcat 的 conf 目录。</p>',
    '<pre id="rh-bridge-message" class="status" role="status" aria-live="polite">尚未启用。</pre>',
    '</section>'
  ].join('');
  const target = document.querySelector('main');
  if (target) target.after(root); else document.body.append(root);
  const status = (v) => { $('rh-bridge-message').textContent = typeof v === 'string' ? v : JSON.stringify(v, null, 2); };
  const call = async (path, options) => {
    const reply = await fetch(path, Object.assign({cache:'no-store',credentials:'same-origin'}, options || {}));
    const value = await reply.json().catch(() => ({error:'未返回 JSON，请检查扩展是否安装'}));
    if (!reply.ok) throw new Error(value.error || 'HTTP '+reply.status);
    return value;
  };
  async function update() {
    const data = await call('api/bridge/config');
    if (typeof data.workflowId === 'string' && $('workflowId')) $('workflowId').value = data.workflowId;
    if (data.region && $('region')) $('region').value = data.region;
    if (Array.isArray(data.bindings) && data.bindings.length) $('rh-bridge-map').value = JSON.stringify(data.bindings,null,2);
    $('rh-bridge-enable').checked = !!data.enabled;
    if ($('apiKey') && data.apiKeyConfigured) $('apiKey').placeholder = '密钥已保存；无需重复输入';
    status({ready:data.ready,enabled:data.enabled,workflowId:data.workflowId || '(未配置)',apiKeyConfigured:data.apiKeyConfigured,
      proxyUrl:data.videoProxyUrl,message:data.ready?'云端代理已准备好，请将原版视频服务 API 地址设置为兼容地址。':'请补全工作流 ID、密钥与节点映射。'});
  }
  async function perform(button, fn) {
    button.disabled = true;
    try { await fn(); } catch(e) { status('操作失败：'+e.message); } finally { button.disabled = false; }
  }
  $('rh-bridge-copy').onclick = () => {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(proxy).then(()=>status('兼容 API 地址已复制。'),()=>status('请手动复制：'+proxy));
    } else status('请手动复制：'+proxy);
  };
  $('rh-bridge-refresh').onclick = () => perform($('rh-bridge-refresh'),update);
  $('rh-bridge-save').onclick = () => perform($('rh-bridge-save'),async() => {
    const workflowId = $('workflowId').value.trim();
    const apiKey = $('apiKey').value.trim();
    if (!/^\d{1,16}$/.test(workflowId)) throw new Error('请先在上方填写真实 RunningHub 工作流数字 ID。');
    const bindings = JSON.parse($('rh-bridge-map').value);
    if (!Array.isArray(bindings) || bindings.length===0) throw new Error('至少需要一条节点映射。');
    const enabled = $('rh-bridge-enable').checked;
    if (enabled && !window.confirm('是否确认启用云端视频代理？原版视频生成可能触发 RunningHub 付费任务。')) return;
    const data = await call('api/bridge/config',{method:'POST',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({region:$('region').value,apiKey,workflowId,bindings,enabled})});
    status({ready:data.ready,enabled:data.enabled,workflowId:data.workflowId,apiKeyConfigured:data.apiKeyConfigured,
      message:data.ready?'服务端配置完成。下一步：返回原系统的视频生成接口，把本地 ComfyUI 的 API 地址改为 '+proxy+' 并保存。':'配置已保存，但代理尚未就绪，请确认 API Key 和启用状态。'});
    if ($('apiKey')) $('apiKey').value = '';
  });
  $('rh-bridge-test').onclick = () => perform($('rh-bridge-test'),async() => {
    const result=await call('comfy/system_stats');
    status({result,message:'兼容代理连接成功。此测试不使用真实工作流，也不会消耗生成额度。'});
  });
  update().catch((e)=>status('云端代理尚未安装或无法连接：'+e.message));
})();