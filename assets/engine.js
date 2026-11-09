'use strict';
(function(root){
 const clamp=(x,min=0,max=1)=>Math.max(min,Math.min(max,x));
 const norm=s=>String(s||'').normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/[^a-z0-9]+/g,' ').trim();
 const profiles=root.scapesProfiles,images=root.scapesImages;
 const LYRIC_SHARE=.3;
 function shortTitle(image){
  const original=typeof image==='string'?image:image?.kind==='artist'&&image.artistName?image.artistName:image?.title||'',clean=value=>String(value||'').replace(/^File:/i,'').replace(/\.(?:jpe?g|png|tiff?|webp)$/i,'').replace(/_/g,' ').replace(/\s*[-–]\s*(?:Flickr|Wikimedia Commons)(?:\s*[-–].*)?$/i,'').replace(/\s*\((?:[a-f0-9]{8,}|\d+)\)\s*$/i,'').replace(/^(?:(?:DSC[FNO]?|DFC|DZ\d|IMG|PXL|DMC)[ -]?\d{3,14}[ -]*)+/i,'').replace(/^[,.;:\s-]+/,'').replace(/\s+/g,' ').trim();
  let text=clean(original);if(!text&&typeof image==='object')text=clean(image.caption).replace(/^[.\s]+/,'');
  if(!text)return 'From the collection';
  if(text.length>60||text.split(' ').length>8){
   text=text.replace(/^(?:a|an|the)\s+/i,'').replace(/^(?:(?:dramatic|quiet|peaceful|beautiful|stunning|brightly lit|well lit|colorful|serene|low-angle|panoramic|scenic|nighttime|night)\s+)*(?:view|shot|photograph|photo|image|picture)\s+(?:looking up at|looking down at|of|over)\s+/i,'');
   const clauses=text.split(/(?:[,;.!?]\s+|\s+(?:as|while|where|which|with|against|their|surrounded|bathed|casting|mirrored|glow|glowing|and)\s+)/i);if(clauses[0]?.trim().split(/\s+/).length>=3)text=clauses[0];
   let words=text.split(/\s+/).slice(0,8);while(words.length>1&&(words.join(' ').length>60||/^(?:a|an|the|and|or|of|in|on|at|to|over|under|by|with|as|from|near|into)$/i.test(words[words.length-1])))words.pop();text=words.join(' ');
  }
  if(Array.from(text).length>60)text=Array.from(text).slice(0,57).join('')+'…';return text.charAt(0).toUpperCase()+text.slice(1);
 }
 const broad=new Set(['country','jazz','electronic','folk','chill','rock','classical','pop','hiphop','world']);
 const aliases=Object.values(profiles).flatMap(p=>p.aliases.map(a=>({alias:norm(a),profile:p}))).sort((a,b)=>b.alias.length-a.alias.length);
 function identify(text){const value=' '+norm(text)+' ';return aliases.find(a=>value.includes(' '+a.alias+' '))?.profile||null;}
 function descendant(p,parent){while(p&&p.parent){if(p.parent===parent)return true;p=profiles[p.parent];}return false;}
 function baseContext(song,settings,previous){
  if(settings.style&&settings.style!=='auto'&&profiles[settings.style])return{profile:profiles[settings.style],blend:[{id:settings.style,weight:1}],evidence:'Your selected style',precision:'manual'};
  if(settings.mode!=='auto'){
   const id=settings.mode==='acoustic'?'folk':settings.mode;
   const profile=profiles[id]||profiles.chill;return{profile,blend:[{id:profile.id,weight:1}],evidence:'Your selected mood',precision:'manual'};
  }
  const alternative=/^(alternative|alternative music|alternative indie)$/.test(norm(song.genre)),songwriter=/^singer songwriter$/.test(norm(song.genre));
  const track=alternative?profiles.rock:identify(song.genre),world=root.ScapesWorlds?.resolve(song),hint=profiles[world?.profile];
  const familyAllowed=p=>!track||p.family===track.family||alternative&&['rock','folk','pop'].includes(p.family)||songwriter&&['folk','country','rock','pop'].includes(p.family)||hint&&world?.broadGenreFallbacks?.includes(track?.id)&&p.family===hint.family;
  const canRefine=track&&Object.values(profiles).some(p=>p.parent===track.id);
  if(track&&!broad.has(track.id)&&!canRefine)return{profile:track,blend:[{id:track.id,weight:1}],evidence:'Song genre',precision:'specific'};
  if(hint&&familyAllowed(hint)&&(!track||broad.has(track.id))&&hint.id!==track?.id)return{profile:hint,blend:[{id:hint.id,weight:1}],evidence:'Researched artist direction · broad recording genre',precision:'editorial'};
  const scores=new Map();
  for(const tag of song.artistGenres||[]){
   const p=identify(tag.name);if(!p||tag.count<=0)continue;
   // Artist-wide tags cannot override a different, known track family.
   if(!familyAllowed(p))continue;
   if(track&&!broad.has(track.id)&&p.id!==track.id&&!descendant(p,track.id))continue;
   const weight=Math.log2(1+Math.min(100,tag.count))*(broad.has(p.id)?.45:1);
   scores.set(p.id,(scores.get(p.id)||0)+weight);
  }
  const ordered=[...scores].sort((a,b)=>b[1]-a[1]);
  const detailed=ordered.filter(([id])=>!broad.has(id)&&!ordered.some(([child])=>descendant(profiles[child],id)));
  if(detailed.length){
   const top=detailed[0],second=detailed[1];let blend=[{id:top[0],weight:1}];
   if(second&&second[1]/top[1]>.8){const sum=top[1]+second[1];blend=[{id:top[0],weight:top[1]/sum},{id:second[0],weight:second[1]/sum}];}
   return{profile:profiles[top[0]],blend,evidence:'Artist genre tags'+(blend.length>1?' · blended styles':''),precision:'artist'};
  }
  const fallback=track||(ordered.length?profiles[ordered[0][0]]:null);
  if(fallback)return{profile:fallback,blend:[{id:fallback.id,weight:1}],evidence:track?'Song genre · broad match':'Artist genre · broad match',precision:'broad'};
  if(previous&&previous.artistKey===norm(song.artist)&&previous.albumKey===norm(song.album)&&song.artist&&song.album&&previous.precision!=='fallback')return{...previous,evidence:'Recent album context · awaiting recording evidence',precision:'album-memory'};
  return{profile:profiles.chill,blend:[{id:'chill',weight:1}],evidence:song.title?'Gentle scenery · musical style not yet identified':'Waiting for music',precision:'fallback'};
 }
 function context(song,settings,previous){const ctx=baseContext(song,settings,previous),automatic=settings.mode==='auto'&&settings.style==='auto';return{...ctx,artistKey:norm(song.artist),albumKey:norm(song.album),world:automatic?root.ScapesWorlds?.resolve(song)||null:null,recording:automatic?[song.artist||'',song.album||'',song.title||''].join('|'):'',lyricSignal:automatic?(song.lyricVector?.length===384?'semantic':Object.keys(song.lyricCues||{}).sort().join(',')):''};}
 function dot(a,b){if(!a||!b||a.length!==b.length)return null;let total=0;for(let i=0;i<a.length;i++)total+=a[i]*b[i];return clamp(total,-1,1);}
 function vectorFit(a,b){const value=dot(a,b);return value===null?null:clamp((value-.06)/.58);}
 function tagFit(image,tags){if(!tags.length)return 0;let matched=0;for(const tag of tags)if(image.tags.includes(tag))matched++;return matched/Math.sqrt(tags.length*Math.max(5,image.tags.length));}
 function wide(image){const ratio=image.width/image.height;return ratio>=1.5&&ratio<=2.15&&(image.kind==='artist'?image.width>=1920&&image.height>=1080:image.remote?image.width>=3840&&image.height>=2160:image.width>=3000&&image.height>=1600);}
 function lyricShare(song){const limit=song.lyricsSource?.maxLyricWeight;return Number.isFinite(limit)?clamp(limit,0,LYRIC_SHARE):LYRIC_SHARE;}
 function affinity(image,ctx){if(!image.remote)return image.families[ctx.profile.family]||0;let value=0;for(const piece of ctx.blend){const cos=dot((root.scapesVectors||{})['style:'+piece.id],(root.scapesVectors||{})['image:'+image.key]);value+=clamp(((cos??0)-.12)/.4)*piece.weight;}return value; /* Search family is provenance, not musical-fit evidence. */}
 function styleFit(image,ctx,song){
  if(image.kind==='artist')return image.artistId&&image.artistId===song.artistIdentity?.mbid?1:0;
  const vectors=root.scapesVectors||{};let semantic=0,hasVectors=false,lexical=0,energy=0,art=0,space=0;
  for(const piece of ctx.blend){const p=profiles[piece.id],v=vectorFit(vectors['style:'+p.id],vectors['image:'+image.key]);if(v!==null){semantic+=v*piece.weight;hasVectors=true;}lexical+=tagFit(image,p.tags)*piece.weight;energy+=(1-Math.abs(image.energy-p.energy))*piece.weight;art+=p.art*piece.weight;space+=p.space*piece.weight;}
  const musicalAffinity=affinity(image,ctx);
  const medium=image.kind==='art'?(.5+.5*art):image.kind==='space'?space:1;
  // Semantic retrieval plus curated musical context prevents literal word matches
  // from making unrelated subjects win. All are components of the 70% style term.
  // Online captions lack the reviewed tag vocabulary of bundled assets. Give that
  // missing tag component to semantics, instead of penalising sparse metadata.
  const value=hasVectors?(image.remote?.65*semantic:.42*semantic+.23*clamp(lexical*2))+.22*musicalAffinity+.08*energy+.05*medium:.5*clamp(lexical*2)+.35*musicalAffinity+.1*energy+.05*medium;
  const artistFit=ctx.world?vectorFit(vectors['world:'+ctx.world.id],vectors['image:'+image.key]):null;
  if(artistFit===null)return clamp(value);
  const worldScore=.65*artistFit+.35*clamp(tagFit(image,ctx.world.tags)*2.5),worldWeight=(ctx.world.album||ctx.world.song)?.75:.6;
  const mismatch=(ctx.world.avoidTags||[]).filter(t=>image.tags.includes(t)).length,nightCity=ctx.world.quietCity&&image.tags.includes('city')&&image.tags.includes('night');
  const worldPenalty=Math.min(.28,.09*mismatch+(nightCity?.14:0));
  return clamp((1-worldWeight)*value+worldWeight*worldScore-worldPenalty);
 }
 function lyricFit(image,song){
  const vector=Array.isArray(song.lyricVector)&&song.lyricVector.length===384?song.lyricVector:null;
  const semantic=vectorFit(vector,(root.scapesVectors||{})['image:'+image.key]);
  const cues=Object.entries(song.lyricCues||{}).filter(([,v])=>Number.isFinite(v)&&v>0);
  const total=cues.reduce((n,[,v])=>n+v,0);
  const literal=total?cues.reduce((n,[k,v])=>n+(image.tags.includes(k)?v:0),0)/total:null;
  if(semantic!==null)return{fit:semantic,method:'semantic'};
  if(literal!==null)return{fit:literal,method:'keywords'};
  return{fit:0,method:'none'};
 }
 function rank(ctx,song,settings,history=[],transition={}){
  const type=settings.imageType||'mixed';let pool=Object.values(images).filter(image=>{
   if(image.disabled||!wide(image)||root.ScapesCuration&&!root.ScapesCuration.allowed(image))return false;
   if(image.kind==='artist')return image.artistId===song.artistIdentity?.mbid&&(type==='artists'||((type==='mixed'||type==='photos')&&settings.artistPhotos!=='off'));
   if(image.tags.includes('performance'))return false;
   if(type==='artists')return false;
   if(type==='art')return image.kind==='art';if(type==='space')return image.kind==='space';
   if(type==='photos'&&image.kind==='art')return false;
   const worldCos=ctx.world?dot((root.scapesVectors||{})['world:'+ctx.world.id],(root.scapesVectors||{})['image:'+image.key]):null;
   if(affinity(image,ctx)<.3&&!(worldCos!==null&&worldCos>=.3))return false;
   if(image.kind==='space'&&ctx.profile.space<.15&&!(ctx.world?.tags.includes('space')&&worldCos>=.28))return false;
   return true;
  });
  if(ctx.world?.avoidTags?.length){const focused=pool.filter(p=>p.kind==='artist'||!ctx.world.avoidTags.some(t=>p.tags.includes(t)));if(focused.filter(p=>p.kind!=='artist').length>=6)pool=focused;}
  const candidates=pool.length?pool:Object.values(images).filter(p=>p.kind==='photo'&&!p.disabled&&wide(p)&&(!root.ScapesCuration||root.ScapesCuration.allowed(p)));
  const repeatWindow=Math.min(60,Math.max(5,Math.floor(candidates.length*.65)),Math.max(0,candidates.length-1));
  const recent=history.slice(-repeatWindow);
  const artistCount=candidates.filter(p=>p.kind==='artist').length;
  const artistWindow=type==='artists'?repeatWindow:settings.artistPhotos==='often'?Math.min(18,Math.max(6,artistCount*2)):Math.min(24,Math.max(18,artistCount*3));
  const last=images[history[history.length-1]],recentArt=history.slice(-6).filter(k=>images[k]?.kind==='art').length;
  return candidates.map(image=>{
   const style=styleFit(image,ctx,song),lyrics=lyricFit(image,song);
   const useLyrics=settings.mode==='auto'&&settings.style==='auto'&&lyrics.method!=='none';
   const lyricWeight=useLyrics?lyricShare(song):0,styleWeight=1-lyricWeight;
   const score=styleWeight*style+lyricWeight*lyrics.fit;
   const gap=type==='artists'?0:settings.artistPhotos==='often'?2:5;
   const artistTooSoon=image.kind==='artist'&&gap>0&&history.slice(-gap).some(k=>images[k]?.kind==='artist');
   const similarity=last?dot((root.scapesVectors||{})['image:'+last.key],(root.scapesVectors||{})['image:'+image.key]):0;
   const duplicate=(image.kind==='artist'?history.slice(-artistWindow):recent).includes(image.key)||artistTooSoon,clusterRepeat=last?.cluster===image.cluster;
   const mediumBalance=type==='mixed'&&image.kind==='art'?(ctx.profile.art-recentArt/Math.max(1,Math.min(6,history.length)))*.06:0;
   const sequencePenalty=(clusterRepeat?.07:0)+(similarity>.92?.12:0);
   const room=root.ScapesCuration,sequence=room?room.sequence(image,history,images,transition):{continuity:0,scenePenalty:0,brightnessPenalty:0,palette:.5},quality=room?room.quality(image):.74,taste=room?room.tasteFit(image):0;
   const diversity=room?.diversity?room.diversity(image,history,images):{creatorRepeat:false,placeRepeat:false,sourcePenalty:0};
   const ambientBias=room?room.ambientBias(image,settings):0,compositionPenalty=room?.compositionPenalty?.(image,ctx,settings)||0,roomCalm=(ctx.profile.energy??.5)<=.5||!!room?.evening(settings);
   const utility=score-compositionPenalty-sequencePenalty+mediumBalance+sequence.continuity-sequence.scenePenalty-sequence.brightnessPenalty-diversity.sourcePenalty+.18*(quality-.65)+taste+ambientBias+(image.kind==='artist'&&image.width>=3840?.04:0);
   const artistDue=image.kind==='artist'&&!duplicate&&quality>=.55&&taste>=0&&history.length>=gap;
   return{key:image.key,image,styleFit:style,lyricFit:lyrics.fit,styleWeight,lyricWeight,lyricMethod:useLyrics?lyrics.method:'none',score,utility,duplicate,artistDue,quality,taste,paletteFit:sequence.palette,lastPaletteFit:sequence.lastPalette,runningPaletteFit:sequence.runningPalette,colourKnown:sequence.colourKnown,colourReset:sequence.reset,brightnessDelta:sequence.brightnessDelta,scenePenalty:sequence.scenePenalty,brightnessPenalty:sequence.brightnessPenalty,ambientBias,compositionPenalty,roomCalm,...diversity};
  }).sort((a,b)=>Number(a.duplicate)-Number(b.duplicate)||b.utility-a.utility||a.key.localeCompare(b.key));
 }
 function explain(result,ctx){
  if(!result)return 'Waiting for an image';
  const p=result.image,tags=ctx.world?[...ctx.profile.tags,...ctx.world.tags]:ctx.profile.tags,shared=p.tags.filter(t=>tags.includes(t)).slice(0,4);
  if(p.kind==='artist')return 'Photograph of '+p.artistName+' · matched artist identity and source caption';
  return (ctx.world?ctx.world.evidence:ctx.profile.name)+' · '+(shared.length?shared.join(', '):'semantic visual match')+' · '+(result.lyricWeight?Math.round(100*(1-result.lyricWeight))+'% musical context / '+Math.round(100*result.lyricWeight)+'% '+(result.lyricMethod==='semantic'?'lyrical meaning':'lyric cues'):'musical context');
 }
 function pick(ranked,history=[],random=Math.random){
  if(!ranked.length)return null;
  const paced=ranked.filter(item=>!(item.image.kind==='artist'&&item.duplicate)),usable=paced.length?paced:ranked;
  const fresh=usable.filter(item=>!item.duplicate);let available=fresh.length?fresh:usable;
  // Keep a due, identity-matched artist turn even when all photos share one concert.
  const otherSeries=available.filter(item=>!item.seriesRepeat||item.artistDue);if(otherSeries.length)available=otherSeries;
  const scenes=available.filter(r=>r.image.kind!=='artist'),scenePeak=scenes.length?Math.max(...scenes.map(r=>r.score)):0;
  const relevanceTolerance=scenes.length>=24?.10:.12,relevanceFloor=scenePeak-relevanceTolerance;available=available.filter(r=>r.image.kind==='artist'||r.score>=relevanceFloor);
  const peakScore=Math.max(...available.map(r=>r.score)),sourceVariety=available.filter(r=>r.artistDue||!r.creatorRepeat&&!r.placeRepeat&&r.quality>=.55&&r.score>=peakScore-.25);if(sourceVariety.length)available=sourceVariety;
  // Prefer less crowded measured photographs when enough equally relevant choices exist.
  const openScenes=available.filter(r=>!(r.roomCalm&&r.image.kind==='photo'&&Number.isFinite(r.image.visual?.clutter)&&r.image.visual.clutter>.35));
  if(openScenes.filter(r=>r.image.kind==='photo'&&Number.isFinite(r.image.visual?.clutter)&&r.image.visual.clutter<=.35).length>=6)available=openScenes;
  const dueArtists=available.filter(item=>item.artistDue);if(dueArtists.length){const varied=dueArtists.filter(item=>!item.seriesRepeat),choices=varied.length?varied:dueArtists;const fourK=choices.filter(item=>item.image.width>=3840&&item.quality>=.6);available=fourK.length?fourK:choices;}
  if(!dueArtists.length){const measured=available.filter(r=>r.colourKnown&&!r.colourReset);if(measured.length){const bestColour=Math.max(...measured.map(r=>r.paletteFit));if(bestColour>=.55){const smooth=available.filter(r=>r.colourKnown&&r.paletteFit>=Math.max(.45,bestColour-.22));if(smooth.length)available=smooth;}}}
  let selectionReason=dueArtists.length?'artist_turn':'balanced';
  // Offer a relevant alternate medium when it has been absent, within a quality band.
  if(!dueArtists.length&&root.ScapesCuration){const peak=Math.max(...available.map(r=>r.score)),eligible=available.filter(r=>r.score>=peak-.08&&r.quality>=.55&&(!r.colourKnown||r.colourReset||r.paletteFit>=.55));for(const kind of ['art','space']){const gap=kind==='art'?7:10;if(history.slice(-gap).some(k=>images[k]?.kind===kind))continue;const choices=eligible.filter(r=>r.image.kind===kind);if(choices.length){available=choices;selectionReason='medium_variety';break;}}}
  const best=Math.max(...available.map(item=>item.utility));
  // Explore a band of good matches, rather than greedily cycling the top six.
  // The score itself remains the same 70/30 match; this is selection diversity.
  const byQuality=[...available].sort((a,b)=>b.utility-a.utility);
  const ninth=byQuality[Math.min(8,byQuality.length-1)].utility;
  const band=Math.min(.20,Math.max(.16,best-ninth));
  const candidates=available.filter(item=>item.utility>=best-band);
  const counts=new Map();for(const key of history)counts.set(key,(counts.get(key)||0)+1);
  const creators=new Map(),creatorOf=item=>root.ScapesCuration?.creator(item.image)||'';for(const item of candidates){const name=creatorOf(item);if(name)creators.set(name,(creators.get(name)||0)+1);}
  const weights=candidates.map(item=>{
   const count=counts.get(item.key)||0,last=history.lastIndexOf(item.key);
   const gap=last<0?history.length+1:history.length-last;
   const quality=Math.exp(3*(item.utility-best));
   const novelty=(last<0?2:1+Math.min(2,gap/12))/(1+count);
   const author=creatorOf(item);return quality*novelty/(author?creators.get(author):1);
  });
  const total=weights.reduce((a,b)=>a+b,0);let draw=Math.max(0,Math.min(.999999999,random()))*total;
  for(let i=0;i<candidates.length;i++){draw-=weights[i];if(draw<=0)return{...candidates[i],selectionReason,bestRelevantScore:scenePeak,relevanceTolerance,relevanceLoss:candidates[i].image.kind==='artist'?0:Math.max(0,scenePeak-candidates[i].score)};}
  const chosen=candidates[candidates.length-1];return{...chosen,selectionReason,bestRelevantScore:scenePeak,relevanceTolerance,relevanceLoss:chosen.image.kind==='artist'?0:Math.max(0,scenePeak-chosen.score)};
 }
 function signature(ctx){return ctx.blend.map(p=>p.id+':'+p.weight.toFixed(4)).join('|')+'|'+(ctx.world?.id||'')+'|'+(ctx.recording||'')+'|'+(ctx.lyricSignal||'');}
 root.ScapesEngine={context,rank,pick,explain,identify,dot,wide,signature,profiles,images,lyricShare:LYRIC_SHARE,sourceLyricShare:lyricShare,shortTitle};
})(window);
