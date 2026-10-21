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
    note.textContent='这是嵌入原网页的 RunningHub 独立生成操作台。暂未接管原系统的「生成视频」按钮，生成结果不会自动写入原素材库。';
    var frame=document.createElement('iframe');frame.title='RunningHub 云端工作流配置';frame.src='/runninghub/';
    frame.loading='lazy';frame.style.cssText='display:block;width:100%;height:960px;min-height:550px;border:0;background:#101722';
    var status=document.createElement('span');status.style.cssText='font-size:12px;color:var(--text-secondary,#aaa)';
    status.textContent='未检测服务';
    head.append(icon,label,status,button);content.append(note,frame);card.append(head,content);
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