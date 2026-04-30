//
//
// Main functions
//
// 

// --- Node.js compatibility shim ---------------------------------------------
// The upstream script (designed for the Sora sandbox) assumes a global
// `fetchv2` is provided by the host. When we run it under Node.js we need
// to define one ourselves. Identical behaviour to the previous version of
// this file. Supports:
//   fetchv2(url)
//   fetchv2(url, headersObj)
//   fetchv2(url, {}, "POST", bodyJson)
if (typeof fetchv2 === "undefined") {
  // eslint-disable-next-line no-global-assign
  globalThis.fetchv2 = async function fetchv2(url, optionsOrHeaders = undefined, method, body) {
    const init = {};

    if (method === "POST") {
      init.method = "POST";
      init.body = body;

      if (optionsOrHeaders && Object.keys(optionsOrHeaders).length > 0) {
        if (optionsOrHeaders.headers) {
          init.headers = optionsOrHeaders.headers;
        } else {
          init.headers = optionsOrHeaders;
        }
      }
    } else {
      if (optionsOrHeaders && Object.keys(optionsOrHeaders).length > 0) {
        if (optionsOrHeaders.headers) {
          Object.assign(init, optionsOrHeaders);
        } else {
          init.headers = optionsOrHeaders;
        }
      }
    }

    return fetch(url, init);
  };
}
// ---------------------------------------------------------------------------

const DENO_PROXY_PREFIX = "https://deno-proxies-sznvnpnxwhbv.deno.dev/?url=";
const ANIKAI_HOME_TITLE_REGEX = /<title>Home - AnimeKai - Watch Free Anime Online, Stream Subbed &amp; Dubbed Anime in HD<\/title>/i;
const ANIKAI_CHECK_TIMEOUT_MS = 900;
let animekaiBlockCheckPromise = null;

function proxyUrl(url) {
  return DENO_PROXY_PREFIX + encodeURIComponent(url);
}

async function isAnimekaiBlockedForUser() {
  if (!animekaiBlockCheckPromise) {
    animekaiBlockCheckPromise = (async () => {
      let timeoutId;
      try {
        const htmlText = await Promise.race([
          fetchv2("https://anikai.to/home").then(response => response.text()),
          new Promise(resolve => {
            timeoutId = setTimeout(() => resolve(""), ANIKAI_CHECK_TIMEOUT_MS);
          })
        ]);

        if (timeoutId) {
          clearTimeout(timeoutId);
        }

        return !ANIKAI_HOME_TITLE_REGEX.test(htmlText);
      } catch (error) {
        if (timeoutId) {
          clearTimeout(timeoutId);
        }
        console.error("Animekai accessibility check failed:" + error);
        return true;
      }
    })();
  }

  return animekaiBlockCheckPromise;
}

async function searchResults(query) {
  const encodeQuery = keyword => encodeURIComponent(keyword);
  const MIN_RESULTS_FAST_RETURN = 8;
  const HIGH_CONFIDENCE_SCORE = 900;
  const queryNormalized = query.toLowerCase().trim();
  const queryTokens = queryNormalized.split(/\s+/).filter(Boolean);
  const isSpecificQuery = queryTokens.length <= 2 && queryNormalized.length >= 5;

  const decodeHtmlEntities = (str) => {
    if (!str) return str;
    return str.replace(/&#(\d+);/g, (match, dec) => String.fromCharCode(dec))
              .replace(/&quot;/g, '"')
              .replace(/&amp;/g, '&')
              .replace(/&lt;/g, '<')
              .replace(/&gt;/g, '>');
  };

  const fuzzyMatch = (query, title) => {
    const q = query.toLowerCase().trim();
    const t = title.toLowerCase().trim();
    
    if (t === q) return 1000;
    
    if (t.startsWith(q + ' ') || t.startsWith(q + ':') || t.startsWith(q + '-')) return 950;
    
    const wordBoundaryRegex = new RegExp(`\\b${q.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`);
    if (wordBoundaryRegex.test(t)) return 900;
    
    const qTokens = q.split(/\s+/).filter(token => token.length > 0);
    const tTokens = t.split(/[\s\-:]+/).filter(token => token.length > 0);
    
    const stopwords = new Set(['the', 'a', 'an', 'and', 'or', 'of', 'in', 'on', 'at', 'to', 'for', 'with']);
    
    let score = 0;
    let exactMatches = 0;
    let partialMatches = 0;
    let significantMatches = 0;
    
    qTokens.forEach(qToken => {
      const isStopword = stopwords.has(qToken);
      let bestMatch = 0;
      let hasExactMatch = false;
      
      tTokens.forEach(tToken => {
        let matchScore = 0;
        
        if (tToken === qToken) {
          matchScore = isStopword ? 25 : 120;
          hasExactMatch = true;
          if (!isStopword) significantMatches++;
        }
        else if (qToken.includes(tToken) && tToken.length >= 3 && qToken.length <= tToken.length + 2) {
          matchScore = isStopword ? 8 : 40;
          if (!isStopword) significantMatches++;
        }
        else if (tToken.startsWith(qToken) && qToken.length >= 3) {
          matchScore = isStopword ? 12 : 70;
          if (!isStopword) significantMatches++;
        }
        else if (qToken.length >= 4 && tToken.length >= 4) {
          if (Math.abs(qToken.length - tToken.length) > 2) {
            return;
          }

          if (qToken[0] !== tToken[0]) {
            return;
          }

          const dist = levenshteinDistance(qToken, tToken);
          const maxLen = Math.max(qToken.length, tToken.length);
          const similarity = 1 - (dist / maxLen);
          
          if (similarity > 0.8) {
            matchScore = Math.floor(similarity * 60);
            if (!isStopword) significantMatches++;
          }
        }
        
        bestMatch = Math.max(bestMatch, matchScore);
      });
      
      if (bestMatch > 0) {
        score += bestMatch;
        if (hasExactMatch) exactMatches++;
        else partialMatches++;
      }
    });
    
    const significantTokens = qTokens.filter(t => !stopwords.has(t)).length;
    
    const requiredMatches = Math.max(1, Math.ceil(significantTokens * 0.8));
    if (significantMatches < requiredMatches) {
      return 0;
    }
    
    if (exactMatches + partialMatches >= qTokens.length) {
      score += 80;
    }
    
    score += exactMatches * 20;
    
    const extraWords = tTokens.length - qTokens.length;
    if (extraWords > 2) {
      score -= (extraWords - 2) * 25;
    }
    
    let orderBonus = 0;
    for (let i = 0; i < qTokens.length - 1; i++) {
      const currentTokenIndex = tTokens.findIndex(t => t.includes(qTokens[i]));
      const nextTokenIndex = tTokens.findIndex(t => t.includes(qTokens[i + 1]));
      
      if (currentTokenIndex !== -1 && nextTokenIndex !== -1 && currentTokenIndex < nextTokenIndex) {
        orderBonus += 15;
      }
    }
    score += orderBonus;
    
    return Math.max(0, score);
  };

  const levenshteinDistance = (a, b) => {
    const matrix = [];
    
    for (let i = 0; i <= b.length; i++) {
      matrix[i] = [i];
    }
    
    for (let j = 0; j <= a.length; j++) {
      matrix[0][j] = j;
    }
    
    for (let i = 1; i <= b.length; i++) {
      for (let j = 1; j <= a.length; j++) {
        if (b.charAt(i - 1) === a.charAt(j - 1)) {
          matrix[i][j] = matrix[i - 1][j - 1];
        } else {
          matrix[i][j] = Math.min(
            matrix[i - 1][j - 1] + 1, 
            matrix[i][j - 1] + 1,     
            matrix[i - 1][j] + 1     
          );
        }
      }
    }
    
    return matrix[b.length][a.length];
  };

  const animekaiSearch = async () => {
    const searchBaseUrl = "https://anikai.to/browser?keyword=";
    const baseUrl = "https://anikai.to";

    const posterHrefRegex = /href="[^"]*" class="poster"/g;
    const titleRegex = /class="title"[^>]*title="[^"]*"/g;
    const imageRegex = /data-src="[^"]*"/g;
    const extractHrefRegex = /href="([^"]*)"/;
    const extractImageRegex = /data-src="([^"]*)"/;
    const extractTitleRegex = /title="([^"]*)"/;
    let useProxy = true;

    const extractResultsFromHTML = (htmlText) => {
      const results = [];
      const posterMatches = htmlText.match(posterHrefRegex) || [];
      const titleMatches = htmlText.match(titleRegex) || [];
      const imageMatches = htmlText.match(imageRegex) || [];
      const minLength = Math.min(posterMatches.length, titleMatches.length, imageMatches.length);

      for (let i = 0; i < minLength; i++) {
        const hrefMatch = posterMatches[i].match(extractHrefRegex);
        const fullHref = hrefMatch ? (hrefMatch[1].startsWith("http") ? hrefMatch[1] : baseUrl + hrefMatch[1]) : null;

        const imageMatch = imageMatches[i].match(extractImageRegex);
        const imageSrc = imageMatch ? imageMatch[1] : null;

        const titleMatch = titleMatches[i].match(extractTitleRegex);
        const cleanTitle = titleMatch ? decodeHtmlEntities(titleMatch[1]) : null;

        if (fullHref && imageSrc && cleanTitle) {
          results.push({
            href: `Animekai:${fullHref}`,
            image: useProxy ? proxyUrl(imageSrc) : imageSrc,
            title: cleanTitle
          });
        }
      }

      return results;
    };

    try {
      const encodedQuery = encodeQuery(query);
      useProxy = await isAnimekaiBlockedForUser();
      const fetchPages = async (pages) => {
        const urls = pages.map(page =>
          page === 1
            ? `${searchBaseUrl}${encodedQuery}`
            : `${searchBaseUrl}${encodedQuery}&page=${page}`
        );

        const responses = await Promise.all(urls.map(url => fetchv2(useProxy ? proxyUrl(url) : url)));
        const htmlTexts = await Promise.all(responses.map(res => res.text()));

        const allResults = [];
        htmlTexts.forEach(html => allResults.push(...extractResultsFromHTML(html)));
        return allResults;
      };

      const page1Results = await fetchPages([1]);
      return {
        page1Results,
        fetchRemaining: () => fetchPages([2, 3])
      };
    } catch (error) {
      console.error("Animekai search error:" + error);
      return {
        page1Results: [],
        fetchRemaining: async () => []
      };
    }
  };

  const oneMoviesSearch = async () => {
    const searchBaseUrl = "https://1movies.bz/browser?keyword=";
    const baseUrl = "https://1movies.bz";

    const posterHrefRegex = /href="([^"]*)" class="poster"/g;
    const titleRegex = /class="title" href="[^"]*">([^<]*)</g;
    const imageRegex = /data-src="([^"]*)"/g;

    const extractResultsFromHTML = (htmlText) => {
      const results = [];
      const posterMatches = [...htmlText.matchAll(posterHrefRegex)];
      const titleMatches = [...htmlText.matchAll(titleRegex)];
      const imageMatches = [...htmlText.matchAll(imageRegex)];
      const minLength = Math.min(posterMatches.length, titleMatches.length, imageMatches.length);

      for (let i = 0; i < minLength; i++) {
        const href = posterMatches[i][1];
        const fullHref = href.startsWith("http") ? href : baseUrl + href;

        const imageSrc = imageMatches[i][1];
        const title = decodeHtmlEntities(titleMatches[i][1]);

        results.push({ href: fullHref, image: "https://deno-proxies-sznvnpnxwhbv.deno.dev/?url=" + encodeURIComponent(imageSrc), title });
      }
      return results;
    };

    try {
      const encodedQuery = encodeQuery(query);
      const fetchPages = async (pages) => {
        const urls = pages.map(page =>
          page === 1
            ? `${searchBaseUrl}${encodedQuery}`
            : `${searchBaseUrl}${encodedQuery}&page=${page}`
        );

        const responses = await Promise.all(urls.map(url => fetchv2("https://deno-proxies-sznvnpnxwhbv.deno.dev/?url=" + encodeURIComponent(url))));
        const htmlTexts = await Promise.all(responses.map(res => res.text()));

        const allResults = [];
        htmlTexts.forEach(html => allResults.push(...extractResultsFromHTML(html)));
        return allResults;
      };

      const page1Results = await fetchPages([1]);
      return {
        page1Results,
        fetchRemaining: () => fetchPages([2, 3])
      };
    } catch (error) {
      console.error("1Movies search error:" + error);
      return {
        page1Results: [],
        fetchRemaining: async () => []
      };
    }
  };

  try {
    const dedupeResults = (results) => {
      const seen = new Set();
      return results.filter(item => {
        const key = `${item.href}|${item.title}`;
        if (seen.has(key)) return false;
        seen.add(key);
        return true;
      });
    };

    const scoreCache = new Map();
    const rankResults = (results) => {
      const scoredResults = results.map(r => {
        const cacheKey = r.title;
        const cached = scoreCache.get(cacheKey);
        const score = cached !== undefined ? cached : fuzzyMatch(query, r.title);
        if (cached === undefined) scoreCache.set(cacheKey, score);
        return { ...r, score };
      });

      const sorted = scoredResults
        .filter(r => r.score > 50)
        .sort((a, b) => b.score - a.score);

      return {
        topScore: sorted[0]?.score || 0,
        items: sorted.map(({ score, ...rest }) => rest)
      };
    };

    const shouldFastReturn = (resultCount, topScore) => {
      if (resultCount >= MIN_RESULTS_FAST_RETURN) return true;
      return isSpecificQuery && resultCount >= 1 && topScore >= HIGH_CONFIDENCE_SCORE;
    };

    // Fetch both sources in parallel so a flood of Animekai results never
    // causes a fast-return before 1Movies has been queried. Previously the
    // server fetched Animekai first, and if it returned ≥8 scored results
    // it returned immediately — meaning 1Movies titles like "From (2022)"
    // were never found for short single-word queries.
    const [animekaiData, oneMoviesData] = await Promise.all([
      animekaiSearch(),
      oneMoviesSearch()
    ]);

    let mergedResults = dedupeResults([
      ...animekaiData.page1Results,
      ...oneMoviesData.page1Results
    ]);

    let rankedResults = rankResults(mergedResults);
    let filteredResults = rankedResults.items;

    if (shouldFastReturn(filteredResults.length, rankedResults.topScore)) {
      return JSON.stringify(filteredResults);
    }

    const [animekaiExtra, oneMoviesExtra] = await Promise.all([
      animekaiData.fetchRemaining(),
      oneMoviesData.fetchRemaining()
    ]);

    mergedResults = dedupeResults([
      ...mergedResults,
      ...animekaiExtra,
      ...oneMoviesExtra
    ]);

    rankedResults = rankResults(mergedResults);
    filteredResults = rankedResults.items;

    return JSON.stringify(filteredResults.length > 0 ? filteredResults : [{
      href: "",
      image: "",
      title: "No results found, please refine query."
    }]);
  } catch (error) {
    return JSON.stringify([{
      href: "",
      image: "",
      title: "Search failed: " + error.message
    }]);
  }
}

async function extractDetails(url) {
  
  if (url.startsWith("Animekai:")) {
    const actualUrl = url.replace("Animekai:", "").trim();
    
    try {
      const useProxy = await isAnimekaiBlockedForUser();
      const response = await fetchv2(useProxy ? proxyUrl(actualUrl) : actualUrl);
      const htmlText = await response.text();
      
      const descriptionMatch = (/<div class="desc text-expand">([\s\S]*?)<\/div>/.exec(htmlText) || [])[1];
      const aliasesMatch = (/<small class="al-title text-expand">([\s\S]*?)<\/small>/.exec(htmlText) || [])[1];
      
      return JSON.stringify([{
        description: descriptionMatch ? cleanHtmlSymbols(descriptionMatch) : "Not available",
        aliases: aliasesMatch ? cleanHtmlSymbols(aliasesMatch) : "Not available",
        airdate: "If stream doesn't load try later or disable VPN/DNS"
      }]);
    } catch (error) {
      console.error("Error fetching Animekai details:" + error);
      return JSON.stringify([{
        description: "Error loading description",
        aliases: "Aliases: Unknown",
        airdate: "Aired: Unknown"
      }]);
    }
  } else {    
    try {
      const response = await fetchv2("https://deno-proxies-sznvnpnxwhbv.deno.dev/?url=" + encodeURIComponent(url));
      const htmlText = await response.text();

      const descriptionMatch = (/<div class="description text-expand">([\s\S]*?)<\/div>/.exec(htmlText) || [])[1];
      const aliasesMatch = (/<small class="al-title text-expand">([\s\S]*?)<\/small>/.exec(htmlText) || [])[1];
      const airdateMatch = (/<li>Released:\s*<span[^>]*>(.*?)<\/span>/.exec(htmlText) || [])[1];

      return JSON.stringify([{
        description: descriptionMatch ? cleanHtmlSymbols(descriptionMatch) : "Not available",
        aliases: aliasesMatch ? cleanHtmlSymbols(aliasesMatch) : "Not aliases",
        airdate: airdateMatch ? cleanHtmlSymbols(airdateMatch) : "Not available"
      }]);
    } catch (error) {
      console.error("Error fetching 1Movies details:"+ error);
      return JSON.stringify([{
        description: "Error loading description",
        aliases: "Not available",
        airdate: "Not available"
      }]);
    }
  }
}

async function extractEpisodes(url) {  
  try {
    if (url.startsWith("Animekai:")) {
      const actualUrl = url.replace("Animekai:", "").trim();
      const useProxy = await isAnimekaiBlockedForUser();
      const htmlText = await (await fetchv2(useProxy ? proxyUrl(actualUrl) : actualUrl)).text();
      const animeIdMatch = (htmlText.match(/<div class="rate-box"[^>]*data-id="([^"]+)"/) || [])[1];
      if (!animeIdMatch) return JSON.stringify([{ error: "AniID not found" }]);

      const tokenResponse = await fetchv2(`https://enc-dec.app/api/enc-kai?text=${encodeURIComponent(animeIdMatch)}`);
      const tokenData = await tokenResponse.json();
      const token = tokenData.result;

      const episodeListUrl = `https://anikai.to/ajax/episodes/list?ani_id=${animeIdMatch}&_=${token}`;
        const episodeListData = await (await fetchv2(useProxy ? proxyUrl(episodeListUrl) : episodeListUrl)).json();
      const cleanedHtml = cleanJsonHtml(episodeListData.result);

      const episodeRegex = /<a[^>]+num="([^"]+)"[^>]+token="([^"]+)"[^>]*>/g;
      const episodeMatches = [...cleanedHtml.matchAll(episodeRegex)];

      const episodes = episodeMatches.map(([_, episodeNum, episodeToken]) => ({
        number: parseInt(episodeNum, 10),
        href: `Animekai:https://anikai.to/ajax/links/list?token=${episodeToken}&_=ENCRYPT_ME`
      }));

      return JSON.stringify(episodes);
    } else {
      const htmlText = await (await fetchv2("https://deno-proxies-sznvnpnxwhbv.deno.dev/?url=" + encodeURIComponent(url))).text();
      const movieIDMatch = (htmlText.match(/<div class="detail-lower"[^>]*id="movie-rating"[^>]*data-id="([^"]+)"/) || [])[1];
      if (!movieIDMatch) return JSON.stringify([{ error: "MovieID not found" }]);

      const tokenResponse = await fetchv2("https://enc-dec.app/api/enc-movies-flix?text=" + encodeURIComponent(movieIDMatch));
      const temp = await tokenResponse.json();
      const token = temp.result;

      const episodeListUrl = `https://1movies.bz/ajax/episodes/list?id=${movieIDMatch}&_=${token}`;
      const episodeListData = await (await fetchv2("https://deno-proxies-sznvnpnxwhbv.deno.dev/?url=" + encodeURIComponent(episodeListUrl))).json();
      const cleanedHtml = cleanJsonHtml(episodeListData.result);

      // Build a map of position → season number so we can tag each episode
      // <a> with the season container it sits inside, regardless of whether
      // data-season appears on the <a> itself or on a wrapper element.
      const seasonBoundaryRegex = /data-season="(\d+)"/g;
      const seasonPositions = [];
      let sbMatch;
      while ((sbMatch = seasonBoundaryRegex.exec(cleanedHtml)) !== null) {
        seasonPositions.push({ index: sbMatch.index, season: parseInt(sbMatch[1], 10) });
      }
      const getSeasonAt = (pos) => {
        let s = 1;
        for (const sp of seasonPositions) {
          if (sp.index <= pos) s = sp.season;
          else break;
        }
        return s;
      };

      // Match any <a> tag that has both eid and num, regardless of attribute order.
      const episodeLinkRegex = /<a\b[^>]*>/g;
      const episodes = [];
      let epMatch;
      while ((epMatch = episodeLinkRegex.exec(cleanedHtml)) !== null) {
        const tag = epMatch[0];
        const eidMatch = /\beid="([^"]+)"/.exec(tag);
        const numMatch = /\bnum="([^"]+)"/.exec(tag);
        if (!eidMatch || !numMatch) continue;
        const num = parseInt(numMatch[1], 10);
        if (isNaN(num)) continue;
        const season = seasonPositions.length > 0 ? getSeasonAt(epMatch.index) : 1;
        episodes.push({
          number: num,
          season,
          href: `https://1movies.bz/ajax/links/list?eid=${eidMatch[1]}&_=ENCRYPT_ME`
        });
      }

      return JSON.stringify(episodes);
    }
  } catch (err) {
    console.error("Error fetching episodes:" + err);
    return JSON.stringify([{ number: 1, href: "Error fetching episodes" }]);
  }
}

async function extractStreamUrl(url) {
  let source, actualUrl;
  
  if (url.startsWith("Animekai:")) {
    source = "Animekai";
    actualUrl = url.replace("Animekai:", "").trim();
  } else if (url.includes("1movies.bz")) {
    source = "1Movies";
    actualUrl = url.trim();
  } else {
    console.log("Failed to match URL:", url);
    return "Invalid URL format: " + url;
  }
  
  if (source === "Animekai") {
    const headers = {
      "Referer": "https://anikai.to/",
      "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
    };

    try {
      const useProxy = await isAnimekaiBlockedForUser();
      const tokenMatch = actualUrl.match(/token=([^&]+)/);
      if (tokenMatch && tokenMatch[1]) {
        const rawToken = tokenMatch[1];
        const encryptResponse = await fetchv2(`https://enc-dec.app/api/enc-kai?text=${encodeURIComponent(rawToken)}`);
        const encryptData = await encryptResponse.json();
        const encryptedToken = encryptData.result;
        actualUrl = actualUrl.replace('&_=ENCRYPT_ME', `&_=${encryptedToken}`);
      }
      
      const response = await fetchv2(useProxy ? proxyUrl(actualUrl) : actualUrl);
      const text = await response.text();

      let ajaxResultHtml = "";
      try {
        const parsedAjax = JSON.parse(text);
        ajaxResultHtml = parsedAjax?.result || "";
      } catch {}

      const cleanedHtml = cleanJsonHtml(text);
      const cleanedAjaxResultHtml = cleanJsonHtml(ajaxResultHtml);
      const serverHtmlSource = cleanedAjaxResultHtml || cleanedHtml;

      const subRegex = /<div class="server-items lang-group" data-id="sub"[^>]*>([\s\S]*?)<\/div>/;
      const softsubRegex = /<div class="server-items lang-group" data-id="softsub"[^>]*>([\s\S]*?)<\/div>/;
      const dubRegex = /<div class="server-items lang-group" data-id="dub"[^>]*>([\s\S]*?)<\/div>/;
      const subContent = subRegex.exec(serverHtmlSource)?.[1]?.trim() || "";
      const softsubContent = softsubRegex.exec(serverHtmlSource)?.[1]?.trim() || "";
      const dubContent = dubRegex.exec(serverHtmlSource)?.[1]?.trim() || "";

      const extractServerId = (content) => {
        if (!content) return null;
        const preferred = /<span class="server"[^>]*data-lid="([^"]+)"[^>]*>\s*Server\s*1\s*<\/span>/i.exec(content);
        if (preferred?.[1]) return preferred[1];
        return /<span class="server"[^>]*data-lid="([^"]+)"/i.exec(content)?.[1] || null;
      };

      const serverIdDub = extractServerId(dubContent);
      const serverIdSoftsub = extractServerId(softsubContent);
      const serverIdSub = extractServerId(subContent);

      const tokenRequestData = [
        { name: "Dub", data: serverIdDub },
        { name: "Softsub", data: serverIdSoftsub },
        { name: "Sub", data: serverIdSub }
      ].filter(item => item.data);

      const tokenResults = await Promise.all(tokenRequestData.map(item => 
        fetchv2(`https://enc-dec.app/api/enc-kai?text=${encodeURIComponent(item.data)}`)
          .then(res => res.json())
          .then(json => ({ name: item.name, data: json.result }))
          .catch(err => ({ name: item.name, error: err.toString() }))
      ));

      const serverIdMap = { "Dub": serverIdDub, "Softsub": serverIdSoftsub, "Sub": serverIdSub };

      const streamUrls = tokenResults.map(result => ({
        type: result.name,
        url: `https://anikai.to/ajax/links/view?id=${serverIdMap[result.name]}&_=${result.data}`
      }));

      const streamResponses = await Promise.all(
        streamUrls.map(async ({ type, url }) => {
          try {
            const res = await fetchv2(useProxy ? proxyUrl(url) : url);
            const json = await res.json();
            return { type, result: json.result };
          } catch (error) {
            console.log(`Error fetching ${type} stream:` + error);
            return { type, result: null };
          }
        })
      );

      const decryptResults = await Promise.all(
        streamResponses
          .filter(item => item.result)
          .map(item =>
            fetchv2(`https://enc-dec.app/api/dec-kai?text=${item.result}`, headers)
              .then(res => res.json())
              .then(json => {
                console.log(`decrypted${item.type} URL:` + json.result?.url);
                return { name: item.type, url: json.result?.url || null };
              })
              .catch(err => {
                console.log(`Error parsing ${item.type} result:` + err);
                return { name: item.type, url: null };
              })
          )
      );

      const urlMap = Object.fromEntries(decryptResults.map(i => [i.name, i.url]));
      const decryptedSub = urlMap.Sub;
      const decryptedDub = urlMap.Dub;
      const decryptedRaw = urlMap.Softsub;

      async function getStream(url) {
        try {
          // /e/ → /media/ gives the JSON endpoint that returns the encrypted
          // sources. Route via the deno proxy whenever the direct path is
          // blocked by Cloudflare for this server's IP — same useProxy flag
          // every other anikai request in this file already respects.
          const mediaUrl = url.replace("/e/", "/media/");
          const response = await fetchv2(useProxy ? proxyUrl(mediaUrl) : mediaUrl, headers);
          const responseJson = await response.json();
          // When fetched via the proxy, the proxy wraps the upstream JSON in
          // its own envelope ({"result": "<original body as string>"}). Unwrap.
          let mediaPayload = responseJson;
          if (useProxy && typeof responseJson?.result === "string") {
            try {
              mediaPayload = JSON.parse(responseJson.result);
            } catch {
              mediaPayload = null;
            }
          }
          const result = mediaPayload?.result;
          if (!result) return null;
          const finalResponse = await fetchv2(
            "https://enc-dec.app/api/dec-mega",
            { "Content-Type": "application/json" },
            "POST",
            JSON.stringify({ text: result, agent: headers["User-Agent"] })
          );
          const finalJson = await finalResponse.json();
          return finalJson?.result?.sources?.[0]?.file || null;
        } catch {
          return null;
        }
      }

      const [subStream, dubStream, rawStream] = await Promise.all([
        decryptedSub ? getStream(decryptedSub) : Promise.resolve(null),
        decryptedDub ? getStream(decryptedDub) : Promise.resolve(null),
        decryptedRaw ? getStream(decryptedRaw) : Promise.resolve(null)
      ]);

      const streams = [];
      if (subStream) streams.push({ title: "Hardsub English", streamUrl: subStream });
      if (dubStream) streams.push({ title: "Dubbed English",  streamUrl: dubStream });
      if (rawStream) streams.push({ title: "Original audio",  streamUrl: rawStream });

      const final = { streams, subtitles: "" };
      console.log("RETURN: " + JSON.stringify(final));
      return JSON.stringify(final);

    } catch (error) {
      console.log("Animekai fetch error:" + error);
      return "https://error.org";
    }

  } else if (source === "1Movies") {
    const headers = {
      "Referer": "https://1movies.bz/",
      "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
    };

    try {
      const eidMatch = actualUrl.match(/eid=([^&]+)/);
      if (eidMatch && eidMatch[1]) {
        const rawEpisodeToken = eidMatch[1];
        const encryptResponse = await fetchv2(`https://enc-dec.app/api/enc-movies-flix?text=${rawEpisodeToken}`);
        const encryptData = await encryptResponse.json();
        const encryptedToken = encryptData.result;
        actualUrl = actualUrl.replace('&_=ENCRYPT_ME', `&_=${encryptedToken}`);
      }
      
      const response = await fetchv2("https://deno-proxies-sznvnpnxwhbv.deno.dev/?url=" + encodeURIComponent(actualUrl));
      const responseData = await response.json();
      const cleanedHtml = cleanJsonHtml(responseData.result);
      
      const server1Regex = /<div class="server wnav-item"[^>]*data-lid="([^"]+)"[^>]*>\s*<span>Server 1<\/span>/;
      const server1Match = server1Regex.exec(cleanedHtml);
      
      if (!server1Match) {
        console.log("Server 1 not found");
        return "error";
      }
      
      const serverId = server1Match[1];
      const tokenData = await fetchv2(`https://enc-dec.app/api/enc-movies-flix?text=${encodeURIComponent(serverId)}`)
        .then(res => res.json());
      const token = tokenData.result;
      
      if (!token) {
        console.log("Token not found");
        return "error";
      }
      
      const streamUrl = `https://1movies.bz/ajax/links/view?id=${serverId}&_=${token}`;
      const streamResponse = await fetchv2("https://deno-proxies-sznvnpnxwhbv.deno.dev/?url=" + encodeURIComponent(streamUrl));
      const streamData = await streamResponse.json();
      
      if (!streamData.result) {
        console.log("Stream result not found");
        return "error";
      }
      
      const decryptData = await fetchv2(
        `https://enc-dec.app/api/dec-movies-flix?text=${streamData.result}`,
        headers
      ).then(res => res.json());

      console.log("Decrypted response:" + JSON.stringify(decryptData));
      const decryptedUrl = decryptData.result?.url;

      if (!decryptedUrl) {
        console.log("Decryption failed");
        return "error";
      }

      const subListEncoded = decryptedUrl.split("sub.list=")[1]?.split("&")[0];
      let subtitles = "N/A";
      if (subListEncoded) {
        try {
          const subListUrl = decodeURIComponent(subListEncoded);
          const subResponse = await fetchv2(subListUrl);
          subtitles = await subResponse.json();
        } catch {
          subtitles = "N/A";
        }
      }

      const englishSubUrl = Array.isArray(subtitles)
        ? subtitles.find(sub => sub.label === "English")?.file.replace(/\\\//g, "/")
        : "N/A";
      
      const mediaResponse = await fetchv2(decryptedUrl.replace("/e/", "/media/"), headers);
      const mediaJson = await mediaResponse.json();
      const result = mediaJson?.result;

      if (!result) {
        console.log("Media result not found");
        return "error";
      }
      
      const finalResponse = await fetchv2(`https://enc-dec.app/api/dec-rapid?text=${encodeURIComponent(result)}&agent=${encodeURIComponent(headers["User-Agent"])}`);
      const finalJson = JSON.parse(await finalResponse.text());
      
      const m3u8Link = finalJson?.result?.sources?.[0]?.file;
      const m3u8Response = await fetchv2(m3u8Link);
      const m3u8Text = await m3u8Response.text();

      const baseUrl = m3u8Link.substring(0, m3u8Link.lastIndexOf('/') + 1);
      const streams = [];
      const lines = m3u8Text.split('\n');
      
      for (let i = 0; i < lines.length; i++) {
        const line = lines[i].trim();
        if (line.startsWith('#EXT-X-STREAM-INF:')) {
          const resolutionMatch = line.match(/RESOLUTION=(\d+x\d+)/);
          let quality = 'Unknown';
          if (resolutionMatch) {
            const [width, height] = resolutionMatch[1].split('x');
            quality = `${height}p`;
          }
          if (i + 1 < lines.length) {
            streams.push({
              title: quality,
              streamUrl: baseUrl + lines[i + 1].trim()
            });
          }
        }
      }

      const returnValue = {
        streams,
        subtitles: englishSubUrl !== "N/A" ? englishSubUrl : ""
      };
      console.log("RETURN: " + JSON.stringify(returnValue));
      return JSON.stringify(returnValue);

    } catch (error) {
      console.log("1Movies fetch error:" + error);
      return "https://error.org";
    }
  }
}

///
///
/// Helper functions
///
///

function cleanHtmlSymbols(string) {
  if (!string) {
    return "";
  }
  return string
    .replace(/&#8217;/g, "'")
    .replace(/&#8211;/g, "-")
    .replace(/&#[0-9]+;/g, "")
    .replace(/\r?\n|\r/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function cleanJsonHtml(jsonHtml) {
  if (!jsonHtml) {
    return "";
  }
  return jsonHtml
    .replace(/\\"/g, "\"")
    .replace(/\\'/g, "'")
    .replace(/\\\\/g, "\\")
    .replace(/\\n/g, "\n")
    .replace(/\\t/g, "\t")
    .replace(/\\r/g, "\r");
}

function decodeHtmlEntities(text) {
  if (!text) {
    return "";
  }
  return text
    .replace(/&#039;/g, "'")
    .replace(/&quot;/g, "\"")
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&nbsp;/g, " ");
}

// --- Node.js export shim ---------------------------------------------------
// Re-add the exports block that was present in the previous version of this
// script. Without this, `require('./ashi.js')` returns an empty object and
// the server's /search, /details, /episodes and /stream routes all break.
if (typeof module !== "undefined" && module.exports) {
  module.exports = {
    searchResults,
    extractDetails,
    extractEpisodes,
    extractStreamUrl
  };
}