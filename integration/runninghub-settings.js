(function(){
  'use strict';
  // Additive bridge. Does not modify the upstream Vue store or switch its backend provider.
  var ID='canvas-runninghub-card', timer=null;
  function make(){
    var card=document.createElement('section');card.id=ID;card.className='provider-card';
    card.style.cssText='display:block;margin-top:24px;border:1px solid #367cd5;border-radius:14px;overflow:hidden;background:var(--bg-card,#232427);';
    var head=document.createElement('div');head.style.cssText='display:flex;align-items:center;gap:13px;padding:22px 24px;flex-wrap:wrap';
    var icon=document.createElement('span');icon.textContent='☁';icon.style.cssText='font-size:23px;color:#76aeff;background:#183351;border-radius:10px;padding:8px 14px';
    var label=document.createElement('div');label.style.cssText='flex:1;min-width:210px';
    var title=document.createElement('strong');title.textContent='RunningHub 云端工作流';title.style.fontSize='18px';
    var detail=document.createElement('div');detail.textContent='工作流配置 · 图片/视频上传 · 云端任务管理';detail.style.cssText='font-size:13px;margin-top:5px;color:var(--text-secondary,#a3a8b1)';
    label.append(title,detail);
    var button=document.createElement('button');button.type='button';button.textContent='展开配置';button.style.cssText='background:#377de7;color:white;border:0;border-radius:7px;padding:10px 15px;cursor:pointer';
    var content=document.createElement('div');content.hidden=true;content.style.cssText='border-top:1px solid #394352;padding:0';
    var note=document.createElement('p');note.style.cssText='padding:12px 18px 0;margin:0;font-size:12px;line-height:1.6;color:var(--text-secondary,#abb3be)';
    note.textContent='这里包含 RunningHub 云端工作流操作台与原系统视频生成兼容代理。先配置节点映射，再将原版视频 ComfyUI 地址切换到云端代理；原版 WAR 不会被替换。';
    var frame=document.createElement('iframe');frame.title='RunningHub 云端工作流配置';frame.src='/runninghub/';
    frame.loading='lazy';frame.style.cssText='display:block;width:100%;height:960px;min-height:550px;border:0;background:#101722';
    var status=document.createElement('span');status.style.cssText='font-size:12px;color:var(--text-secondary,#aaa)';
    status.textContent='未检测服务';
    var cloudButton=document.createElement('button');
    cloudButton.type='button';
    cloudButton.textContent='填入云端视频地址';
    cloudButton.title='将原系统视频服务的 ComfyUI 地址预填为 RunningHub 代理，仍需点击原系统保存配置';
    cloudButton.style.cssText='background:#205eaa;color:white;border:0;border-radius:7px;padding:10px 15px;cursor:pointer';
    head.append(icon,label,status,button,cloudButton);content.append(note,frame);card.append(head,content);
    cloudButton.addEventListener('click',async function(){
      try {
        var response=await fetch('/runninghub/api/bridge/status',{cache:'no-store',credentials:'same-origin'});
        var config=await response.json();
        if(!response.ok||!config.ready)throw new Error('请先点击展开配置，在第 6 步保存 API Key、工作流 ID 和节点映射，并勾选启用。');
        var upstream=Array.from(document.querySelectorAll('.section-card .provider-card')).find(function(item){
          var title=item.querySelector('.provider-name');
          return title&&title.textContent.indexOf('ComfyUI')>=0&&item.id!=='canvas-runninghub-card';
        });
        if(!upstream)throw new Error('未找到原版的视频 ComfyUI 配置卡片。');
        var input=upstream.querySelector('.api-url-field input');
        if(!input)throw new Error('请先启用原版的 ComfyUI 视频服务开关，再点击本按钮。');
        var proxy='http://127.0.0.1:18080/runninghub/comfy';
        var setter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;
        setter.call(input,proxy);
        input.dispatchEvent(new Event('input',{bubbles:true}));
        input.dispatchEvent(new Event('change',{bubbles:true}));
        status.textContent='已填入云端地址，请点击页面底部「保存配置」';
        status.style.color='#82d8b1';
        window.alert('已在原版视频服务的 API 地址栏填入 RunningHub 云端代理。请点击当前系统设置页面底部的「保存配置」，再在「默认配置」中保持视频生成服务为 ComfyUI（兼容协议名称）。');
      }catch(error){
        window.alert('无法切换：'+error.message);
      }
    });
    button.addEventListener('click',function(){
      content.hidden=!content.hidden;
      button.textContent=content.hidden?'展开配置':'收起配置';
      if(!content.hidden){
        fetch('/runninghub/api/health',{cache:'no-store',credentials:'same-origin'})
        .then(function(res){if(!res.ok)throw new Error('未就绪');return res.json()})
        .then(function(json){status.textContent=json.ok?'● 扩展已连接':'扩展未就绪';status.style.color='#7ad9a3'})
        .catch(function(){status.textContent='未安装 runninghub.war';status.style.color='#e9ae6b'});
      }
    });
    return card;
  }
  function scan(){
    var heads=document.querySelectorAll('.section-title h3');
    for(var i=0;i<heads.length;i++){
      if(heads[i].textContent.trim()!=='视频生成接口配置')continue;
      var parent=heads[i].closest('.section-card');
      if(parent&&!parent.querySelector('#'+ID))parent.appendChild(make());
    }
  }
  function begin(){
    scan();
    new MutationObserver(function(){
      if(timer)return;
      timer=setTimeout(function(){timer=null;scan()},80);
    }).observe(document.getElementById('app')||document.body,{childList:true,subtree:true});
  }
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',begin,{once:true});
  else begin();
})();