'use strict';
(function(root){
 // Scene suggestions are authored visual directions; MiniLM ranks actual source descriptions.
 const scenes={
 country:['rural farmland landscape','country road landscape','prairie sunset','rolling hills farm'],
 bluegrass:['Appalachian mountains farm','Blue Ridge forest landscape','rural wooden barn landscape','Smoky Mountains woodland'],
 outlaw:['desert highway landscape','western badlands landscape','empty desert road','Arizona canyon sunset'],
 americana:['rural American landscape','small town countryside landscape','farmhouse autumn landscape','prairie road'],
 countrymodern:['country road summer','sunlit farmland landscape','open prairie landscape','rural lake sunset'],
 countrypop:['sunflower field landscape','sunlit countryside','coastal road sunset','green rolling hills'],
 traditional:['pastoral farm landscape','horses rural landscape','weathered barn countryside','rural village landscape'],
 countryrock:['desert highway','western canyon landscape','badlands storm landscape','rocky mountain road'],
 jazz:['city street night','city rain reflections','urban waterfront dusk','night architecture'],
 cooljazz:['city night blue','rainy street night','minimal architecture dusk','urban waterfront blue hour'],
 bebop:['geometric architecture city','colorful city night','abstract geometric painting','modern city reflections'],
 smoothjazz:['waterfront evening reflections','city sunset river','calm water dusk','city lights bokeh'],
 fusion:['modern architecture night','colorful urban reflections','abstract architecture','city skyline twilight'],
 swing:['historic city street evening','art deco architecture','city night lights','vintage city architecture'],
 blues:['Mississippi landscape dusk','rain street night','rural road dusk','river delta sunset'],
 soul:['warm city sunset','city waterfront evening','golden landscape reflections','garden evening light'],
 funk:['colorful architecture','neon city night','geometric colorful buildings','urban street reflections'],
 electronic:['modern architecture night','neon city skyline','geometric architecture','aurora landscape'],
 ambient:['misty mountain lake','minimal sea horizon','fog forest landscape','quiet dunes landscape'],
 downtempo:['ocean sunset minimal','city waterfront twilight','soft mist landscape','lake reflections dusk'],
 techno:['brutalist architecture','concrete architecture geometry','modern building night','industrial architecture night'],
 house:['modern city sunset','coastal city twilight','colorful architecture','city night reflections'],
 synthwave:['neon city night','purple sunset skyline','city night magenta','palm trees sunset'],
 trance:['aurora mountains','mountain landscape sunrise','blue ocean horizon','night sky landscape'],
 drumbass:['urban architecture night','industrial city reflections','city skyline night','modern bridge night'],
 folk:['woodland landscape','rural valley landscape','forest path sunlight','mountain cabin landscape'],
 indiefolk:['misty forest landscape','autumn cabin landscape','quiet rural road','foggy mountain lake'],
 traditionalfolk:['rural village landscape','pastoral hills landscape','ancient woodland landscape','farm landscape'],
 chill:['calm lake reflections','mist mountain landscape','ocean horizon sunset','forest light landscape'],
 rock:['rugged mountain landscape','dramatic rocky coast','desert storm landscape','mountain road'],
 classicrock:['canyon desert landscape','rocky coastline sunset','mountain landscape storm','western road'],
 indierock:['rural road overcast','coast mist landscape','city rooftop dusk','autumn forest landscape'],
 psychedelic:['colorful desert landscape','aurora landscape','surreal rock formations','colorful abstract painting'],
 metal:['dramatic mountain storm','volcanic landscape','dark rocky coast','glacier mountain landscape'],
 metalcore:['angular concrete architecture','blue urban architecture night','weathered industrial landscape','stormy forest silhouette'],
 deathcore:['stark concrete architecture','weathered industrial architecture','dark forest storm','angular urban architecture'],
 posthardcore:['rainy urban street','weathered city architecture','urban reflections twilight','expressive abstract painting'],
 postrock:['minimal architecture landscape','misty coast horizon','quiet mountain fog','abstract natural textures'],
 shoegaze:['hazy coast twilight','night water reflections','soft woodland mist','muted abstract painting'],
 industrial:['weathered industrial architecture','empty factory concrete','rust industrial texture','brutalist architecture'],
 grunge:['Pacific Northwest forest rain','weathered urban architecture','Seattle overcast street','woodland rain landscape'],
 progressiverock:['geological abstract patterns','unusual geometric architecture','eroded rock formations','minimal abstract painting'],
 progressivemetal:['repeating concrete architecture','dark geometric architecture','geological abstract texture','stark industrial architecture'],
 darkambient:['shadowed minimal architecture','dark water mist','empty concrete fog','lunar horizon NASA'],
 deephouse:['warm city waterfront evening','architectural reflections dusk','soft urban sunset','modern coastal architecture'],
 trap:['modern city architecture night','urban reflections neon','angular urban architecture','city street twilight'],
 boombap:['weathered urban brick architecture','documentary city street','warm neighborhood architecture','New York street sunset'],
 punk:['industrial city architecture','concrete urban architecture','city street dusk','abandoned industrial landscape'],
 classical:['romantic landscape painting','mountain lake landscape','formal garden landscape','historic landscape painting'],
 baroque:['baroque landscape painting','formal garden landscape','historic palace garden','classical landscape painting'],
 romantic:['romantic landscape painting','dramatic mountain landscape','stormy seascape painting','sunset mountain landscape'],
 impressionist:['impressionist landscape painting','water lilies painting','river landscape painting','garden painting'],
 minimalclassical:['minimal sea horizon','mist lake reflections','minimal architecture','quiet sand dunes'],
 contemporary:['abstract architecture','geometric modern architecture','abstract painting','minimal landscape'],
 cinematic:['dramatic mountain landscape','desert landscape sunset','coastal cliffs landscape','glacier landscape'],
 pop:['colorful coast sunset','sunlit flower field','city skyline sunset','bright garden landscape'],
 dreampop:['misty coast sunset','soft garden landscape','lavender field sunset','dreamy forest fog'],
 hiphop:['city skyline night','urban architecture sunset','city street reflections','concrete architecture'],
 jazzrap:['city waterfront blue hour','urban street dusk','city architecture reflections','rainy city night'],
 world:['cultural landscape mountains','terraced hills landscape','rural village landscape','coastal landscape'],
 reggae:['tropical coast landscape','palm beach sunset','green tropical hills','Caribbean landscape'],
 latin:['colorful colonial architecture','tropical landscape sunset','coastal city evening','desert mountains landscape']
 };
 const art={country:['pastoral landscape painting','rural landscape painting'],jazz:['abstract geometric painting','city night painting'],electronic:['abstract geometric painting','abstract landscape painting'],folk:['woodland landscape painting','rural village painting'],chill:['impressionist water landscape painting','mist landscape painting'],rock:['dramatic landscape painting','mountain storm painting'],classical:['romantic landscape painting','impressionist landscape painting'],pop:['colorful landscape painting','garden impressionist painting'],hiphop:['city painting landscape','geometric abstract painting'],world:['Japanese landscape woodblock','rural landscape painting']};
 const space={ambient:['Earth horizon NASA','Earth limb atmosphere NASA'],techno:['lunar surface NASA','Jupiter cloud bands NASA'],synthwave:['Carina nebula NASA','Earth aurora NASA'],trance:['Earth aurora NASA','Earth horizon ISS NASA'],electronic:['Earth horizon ISS NASA','Jupiter cloud bands NASA','Carina nebula NASA']};
 function facets(p){
  const has=t=>p.tags.includes(t);
  return{scene:(scenes[p.id]||scenes[p.family]||scenes.chill).slice(),palette:has('warm')?'warm ochre and natural green':has('blue')?'cool blue and gray':has('neon')?'luminous saturated colour':'restrained natural colour',lighting:has('night')?'night and reflected light':has('soft')||has('mist')?'diffuse soft light':has('dramatic')?'dramatic directional light':'natural light',texture:has('geometric')?'clean geometry':has('wood')||has('forest')?'organic wood and foliage':has('painting')?'visible brushwork':'natural photographic detail',era:has('nostalgic')?'historic and weathered':has('modern')?'contemporary':'timeless',composition:'wide landscape, uncluttered, 16:9'};
 }
 function plan(ctx,song,settings){
  const p=ctx.profile,type=settings.imageType||'mixed';let queries=[];
  const add=(text,kind)=>{if(!queries.some(q=>q.text===text&&q.kind===kind))queries.push({text,kind});};
  if(type==='mixed'||type==='photos'){
   const primary=ctx.world?.queries||scenes[p.id]||scenes[p.family]||scenes.chill;primary.forEach(text=>{const kind=/NASA/.test(text)?'space':/painting|woodblock/.test(text)?'art':'photo';if(type==='mixed'||kind==='photo')add(text,kind);});
   for(const blend of ctx.blend.slice(1)){const extra=scenes[blend.id]||[];if(extra[0]){const kind=/painting|woodblock/.test(extra[0])?'art':'photo';if(type==='mixed'||kind==='photo')add(extra[0],kind);}}
  }
  if(type==='art'||type==='mixed'&&p.art>=.15){const selected=p.family==='classical'&&['baroque','romantic','impressionist'].includes(p.id)?(scenes[p.id]||[]).filter(s=>s.includes('painting')):ctx.world?(ctx.world.queries.filter(q=>/painting|woodblock/.test(q)).length?ctx.world.queries.filter(q=>/painting|woodblock/.test(q)):[ctx.world.tags.includes('forest')?'woodland landscape painting':ctx.world.tags.includes('water')?'water reflections painting':'muted landscape painting']):art[p.family];(selected||art.chill).forEach((text,i)=>{add(text,'art');if(i===0)add('panoramic landscape painting','art');});}
  if(type==='space'||p.space>=.15&&(type==='mixed'||type==='photos'))(space[p.id]||space.electronic).forEach(text=>add(text,'space'));
  // Put a relevant second medium early, so a successful photo query cannot starve it.
  if(type==='mixed'){
   const preferred=p.space>p.art?'space':'art',alternate=preferred==='art'?'space':'art';
   const groups={photo:queries.filter(q=>q.kind==='photo'),art:queries.filter(q=>q.kind==='art'),space:queries.filter(q=>q.kind==='space')};queries=[];
   while(groups.photo.length||groups.art.length||groups.space.length)for(const kind of ['photo',preferred,alternate])if(groups[kind].length)queries.push(groups[kind].shift());
  }
  if(!queries.length&&(type==='mixed'||type==='photos'))scenes.chill.forEach(text=>add(text,'photo'));
  const identity=song.artistIdentity||{},allowArtist=type==='artists'||((type==='mixed'||type==='photos')&&settings.artistPhotos!=='off');
  if(allowArtist&&identity.mbid&&identity.wikidata){
   const artistQuery={text:identity.name,kind:'artist',artistId:identity.mbid,artistQid:identity.wikidata,artistType:identity.type};
   queries.unshift({...artistQuery,artistSearch:'depicts'});
   if(identity.commonsCategory)queries.unshift({...artistQuery,category:identity.commonsCategory,artistSearch:'category'});
   if(identity.primaryImage)queries.unshift({...artistQuery,file:identity.primaryImage,artistSearch:'primary'});
  }
  // Keyed providers can resolve the confirmed MBID even without a Wikidata image link.
  if(allowArtist&&identity.mbid&&!identity.wikidata)queries.unshift({text:identity.name,kind:'artist',artistId:identity.mbid,artistType:identity.type,artistSearch:'providers'});
  // Final ranking separately caps lyrics by the confidence of their source.
  const cues=Object.entries(song.lyricCues||{}).filter(([,v])=>v>0).sort((a,b)=>b[1]-a[1]);
  const refinements={rain:'rain landscape',home:'rural village landscape',road:'road landscape',water:'lake landscape',forest:'forest landscape',night:'night landscape',sunset:'sunset landscape',mountain:'mountain landscape',desert:'desert landscape',snow:'snow landscape'};
  if(settings.mode==='auto'&&settings.style==='auto'&&(type==='mixed'||type==='photos')){
   const cue=cues.find(([k])=>refinements[k]);if(cue){const text=(scenes[p.id]||scenes[p.family]||scenes.chill)[0]+' '+cue[0];queries.splice(Math.min(4,queries.length),0,{text,kind:'photo',lyricRefinement:true});}
  }
  return{enabled:settings.online!==false,profile:p.id,family:p.family,name:p.name,type,energy:p.energy,queries,facets:facets(p),vector:root.scapesVectors['style:'+p.id],worldId:ctx.world?.id||'',worldVector:ctx.world?root.scapesVectors['world:'+ctx.world.id]:[],artistId:identity.mbid||''};
 }
 root.ScapesDiscovery={plan,facets,scenes};
})(window);
