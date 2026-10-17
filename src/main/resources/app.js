'use strict';
const token=location.hash.slice(1), statusEl=document.getElementById('status'), button=document.getElementById('submit');
let color='ouro';
let maxBytes=32*1024*1024, finished=false;
const headers={Authorization:'Bearer '+token};
async function refresh(){
  if(!token || finished)return;
  try{
    const response=await fetch('status',{headers,cache:'no-store'});
    if(!response.ok){statusEl.textContent=await response.text();button.disabled=true;finished=true;return;}
    const data=await response.json(); maxBytes=data.maxBytes;
    statusEl.textContent=data.player+' · '+data.status;
    document.getElementById('limits').textContent='Até '+Math.round(maxBytes/1024/1024)+' MB e '+data.maxSeconds+' segundos · MP3, WAV, OGG, FLAC, M4A ou vídeo MP4/WEBM · links do YouTube, Spotify e SoundCloud';
    button.disabled=data.claimed;
  }catch(e){statusEl.textContent='Não foi possível conectar. Tentando novamente…';}
}
document.getElementById('audio').addEventListener('change',e=>{const f=e.target.files[0];if(f&&!document.getElementById('title').value)document.getElementById('title').value=f.name.replace(/\.[^.]+$/,'').slice(0,64);});
const mode=()=>document.querySelector('input[name=mode]:checked').value;
for(const radio of document.querySelectorAll('input[name=mode]'))radio.addEventListener('change',()=>{
  document.getElementById('file-box').hidden=mode()!=='file';document.getElementById('link-box').hidden=mode()!=='link';
});
document.getElementById('form').addEventListener('submit',async e=>{
  e.preventDefault(); const title=document.getElementById('title').value.trim();
  if(mode()==='link'){
    const link=document.getElementById('link').value.trim();
    if(!/^https?:\/\//.test(link)){statusEl.textContent='Cole um link que comece com https://';return;}
    button.disabled=true;statusEl.textContent='Enviando link…';
    try{const r=await fetch('link',{method:'POST',headers:{...headers,'Content-Type':'application/json'},body:JSON.stringify({url:link,title,color})});statusEl.textContent=await r.text();if(!r.ok)button.disabled=false;}
    catch(e){statusEl.textContent='Conexão interrompida. Confira /disco meus antes de tentar novamente.';button.disabled=false;}
    return;
  }
  const file=document.getElementById('audio').files[0];
  if(!file||file.size>maxBytes){statusEl.textContent='Selecione um áudio de até '+Math.round(maxBytes/1024/1024)+' MB.';return;}
  if(!title){statusEl.textContent='Dê um nome ao disco.';return;}
  button.disabled=true;statusEl.textContent='Enviando áudio…';
  try{const r=await fetch('upload',{method:'POST',headers:{...headers,'Content-Type':'application/octet-stream','X-Disc-Title':encodeURIComponent(title),'X-Disc-Color':color},body:file});statusEl.textContent=await r.text();}
  catch(e){statusEl.textContent='Conexão interrompida. Confira /disco meus antes de tentar novamente.';}
});
fetch('colors').then(r=>r.json()).then(colors=>{
  const group=document.getElementById('colors');
  for(const c of colors){
    const label=document.createElement('label'),radio=document.createElement('input'),icon=document.createElement('img'),text=document.createElement('span');
    radio.type='radio';radio.name='color';radio.value=c.id;radio.checked=c.id===color;
    icon.src='disc/'+c.id+'.png';icon.width=32;icon.height=32;icon.alt='';text.textContent=c.name;
    radio.addEventListener('change',()=>{color=c.id;document.getElementById('disc-preview').src=icon.src;document.getElementById('color-name').textContent=c.name;});
    label.append(radio,icon,text);group.append(label);
  }
}).catch(()=>{document.getElementById('colors').textContent='Cor padrão: dourado.';});
refresh();setInterval(refresh,3000);
