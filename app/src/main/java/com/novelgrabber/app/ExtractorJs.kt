package com.novelgrabber.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the JS injected into scraped pages — the SAME extraction logic that was
 * verified on desktop (live <p> paragraph rebuild, chapter-ish next links, cover pick).
 * Wrapped in JSON.stringify so evaluateJavascript returns one JSON-encoded string.
 */
object ExtractorJs {

    fun cfg(rule: SiteRule): String = JSONObject().apply {
        put("content", JSONArray(rule.content))
        put("novelTitle", JSONArray(rule.novelTitle))
        put("chapterTitle", JSONArray(rule.chapterTitle))
        put("next", JSONArray(rule.next))
        put("list", JSONArray(rule.list))
        put("first", JSONArray(rule.first))
    }.toString()

    fun build(rule: SiteRule): String = """JSON.stringify((function(){
  var CFG = ${cfg(rule)};
  function qs(s){ try{ return s?document.querySelector(s):null; }catch(e){ return null; } }
  function qsa(s){ try{ return s?Array.prototype.slice.call(document.querySelectorAll(s)):[]; }catch(e){ return []; } }
  function meta(p){ var e=document.querySelector('meta[property="'+p+'"]')||document.querySelector('meta[name="'+p+'"]'); return e?(e.getAttribute('content')||''):''; }
  function chapterish(u){ return /\d|chapter|chapitre|cap-?itulo|read|\/c\//i.test(u||''); }
  function generic(){
     var best=null,bs=0,nodes=document.querySelectorAll('div,article,section,main,td');
     for(var i=0;i<nodes.length;i++){ var n=nodes[i];
        var cls=((n.className||'')+' '+(n.id||'')).toString().toLowerCase();
        if(/nav|menu|footer|header|sidebar|comment|rating|relate|recommend|breadcrumb|disqus|widget/.test(cls)) continue;
        var t=n.innerText||''; if(t.length<200) continue;
        var links=n.querySelectorAll('a').length, ps=n.querySelectorAll('p').length, brs=n.querySelectorAll('br').length;
        var score=t.length - links*120 + ps*30 + brs*10;
        if(score>bs){ bs=score; best=n; }
     }
     return best;
  }
  function bad(node){ var cls=((node.className||'')+' '+(node.id||'')).toString().toLowerCase();
     return /(^|\s)(ad|ads|promo|share|nav|comment|footer|caption)(\s|${'$'}|-)/.test(cls); }
  function getText(el){
     if(!el) return '';
     var ps=el.querySelectorAll('p');
     if(ps && ps.length>=3){
        var parts=[];
        for(var i=0;i<ps.length;i++){ var p=ps[i]; if(bad(p)) continue;
           var t=(p.innerText||p.textContent||'').replace(/ /g,' ').replace(/\s+\n/g,'\n').trim();
           if(t) parts.push(t); }
        var joined=parts.join('\n\n');
        if(joined.replace(/\s/g,'').length>40) return joined;
     }
     return (el.innerText||el.textContent||'').replace(/\r/g,'').replace(/\n{3,}/g,'\n\n').trim();
  }
  function abs(u){ try{ return u?new URL(u,location.href).href:''; }catch(e){ return u||''; } }
  function pickCover(){
     var m=meta('og:image'); if(m) return abs(m);
     var l=document.querySelector('link[rel="image_src"]'); if(l&&l.getAttribute('href')) return abs(l.getAttribute('href'));
     var sels=['.book img','.info-holder img','.books img','.cover img','.book-cover img','.novel-cover img',
               '.summary_image img','figure.cover img','img.cover','.fixed-img img','.book-img img','.series-cover img','.thumb img','.det-info img'];
     for(var k=0;k<sels.length;k++){ var i=document.querySelector(sels[k]); if(i){ var s=i.getAttribute('src')||i.getAttribute('data-src')||i.src; if(s) return abs(s); } }
     var imgs=document.querySelectorAll('img');
     for(var j=0;j<imgs.length;j++){ var im=imgs[j]; var src=(im.getAttribute('src')||'').toLowerCase();
        if(/cover|\/thumbs?\//.test(src) && im.src && !/logo|icon|avatar|banner/.test(src)) return abs(im.src); }
     return '';
  }
  var contentEl=null;
  (CFG.content||[]).some(function(s){ var e=qs(s); if(e){ var t=(e.innerText||e.textContent||'').trim(); if(t.length>40){ contentEl=e; return true; } } return false; });
  if(!contentEl) contentEl=generic();
  var content=getText(contentEl);

  var chapTitle='';
  (CFG.chapterTitle||[]).some(function(s){ var e=qs(s); if(e&&(e.innerText||'').trim()){ chapTitle=e.innerText.trim(); return true; } return false; });
  if(!chapTitle){ var h=qs('h1')||qs('h2'); if(h) chapTitle=(h.innerText||'').trim(); }

  var novelTitle='';
  (CFG.novelTitle||[]).some(function(s){ var e=qs(s); if(e&&(e.innerText||'').trim()){ novelTitle=e.innerText.trim(); return true; } return false; });

  var nextUrl='';
  (CFG.next||[]).some(function(s){ var e=qs(s); if(e&&e.href){ nextUrl=e.href; return true; } return false; });
  if(!nextUrl){ var rn=qs('a[rel=next]'); if(rn&&rn.href&&chapterish(rn.href)) nextUrl=rn.href; }
  if(!nextUrl){ var as=qsa('a'); for(var j2=0;j2<as.length;j2++){ var a=as[j2]; var tx=((a.innerText||'')+' '+(a.title||'')+' '+(a.getAttribute('aria-label')||'')).trim().toLowerCase(); if(a.href && chapterish(a.href) && /(^|\b)(next|next chapter|next page)\b|^›${'$'}|^»${'$'}|下一[章页]|次[のへ]|다음/.test(tx)){ nextUrl=a.href; break; } } }

  var firstUrl='';
  (CFG.first||[]).some(function(s){ var e=qs(s); if(e){ var a=(e.tagName==='A')?e:(e.closest?e.closest('a'):null); if(a&&a.href){ firstUrl=a.href; return true; } } return false; });

  var chapters=[];
  (CFG.list||[]).some(function(s){ var arr=qsa(s); if(arr.length>3){ chapters=arr.map(function(a){ return { title:((a.innerText||a.title||'').trim().slice(0,200)), url:a.href }; }).filter(function(x){ return x.url; }); return true; } return false; });

  return { novelTitle:novelTitle, chapterTitle:chapTitle, content:content, nextUrl:nextUrl, firstUrl:firstUrl,
           ogTitle:meta('og:title'), ogImage:pickCover(), docTitle:document.title,
           chapters:chapters, url:location.href };
})());"""

    /** Clicks a tab whose letters-only text equals one of the tokens (novelarrow "Chapters"). */
    fun tabClick(tokens: List<String>): String {
        val toks = JSONArray(tokens).toString()
        return """(function(){var T=$toks.map(function(x){return x.toLowerCase();});
          var els=document.querySelectorAll('button,a,[role=tab],[role=button],li,span,div');
          for(var i=0;i<els.length;i++){var el=els[i]; if(el.children.length>3) continue;
             var t=(el.textContent||'').replace(/[^a-zA-Z]/g,'').toLowerCase();
             if(T.indexOf(t)>=0){ var c=el.closest('button,a,[role=tab],[role=button]')||el; try{c.click();return true;}catch(e){} }
          } return false;})();"""
    }

    /** Clicks the in-page Next control (novelarrow click-to-advance). */
    fun nextClick(selectors: List<String>): String {
        val arr = JSONArray(selectors).toString()
        return """(function(){var S=$arr;for(var k=0;k<S.length;k++){var els;try{els=document.querySelectorAll(S[k]);}catch(e){continue;}
          for(var i=0;i<els.length;i++){var e=els[i];if(e.offsetParent!==null && !e.disabled){try{e.click();return 'OK';}catch(x){}}}}return 'NONE';})();"""
    }
}
