#!/usr/bin/env python3
# Generador de las blocklists de Hyperion 2.0 (Track 5).
# Dominios reales y bien conocidos. minúsculas, uno por línea.
import os, re

RAW = os.path.expanduser("~/workspace/hyperion/res/raw")

ADS = """doubleclick.net
googlesyndication.com
googleadservices.com
2mdn.net
adservice.google.com
imasdk.googleapis.com
admob.com
adform.net
adform.com
adnxs.com
adsrvr.org
rubiconproject.com
pubmatic.com
openx.net
openx.com
smartadserver.com
mathtag.com
media.net
taboola.com
outbrain.com
revcontent.com
mgid.com
adblade.com
bidvertiser.com
popads.net
popcash.net
adcash.com
propellerads.com
exoclick.com
trafficjunky.net
juicyads.com
adsterra.com
monetag.com
criteo.com
criteo.net
doubleverify.com
moatads.com
moat.com
ads.yahoo.com
advertising.com
adsystem.com
amazon-adsystem.com
aax.amazon-adsystem.com
ads-twitter.com
static.ads-twitter.com
analytics.twitter.com
ads.linkedin.com
snap.licdn.com
ads.pinterest.com
bat.bing.com
ads.microsoft.com
adcolony.com
inmobi.com
smaato.com
smaato.net
mopub.com
applovin.com
ironsource.com
unityads.unity3d.com
vungle.com
chartboost.com
tapjoy.com
fyber.com
flurry.com
millennialmedia.com
admarvel.com
mobfox.com
inner-active.com
jumptap.com
brightroll.com
tremorhub.com
liverail.com
spotxchange.com
springserve.com
freewheel.tv
zedo.com
tribalfusion.com
valueclick.com
fastclick.net
burstnet.com
casalemedia.com
tacoda.net
revenue.net
realmedia.com
adtech.de
adtechus.com
specificclick.net
interclick.com
udmserve.net
yieldmanager.com
yieldmanager.net
rfihub.com
rfihub.net
bluekai.com
exelator.com
eyeota.net
liveramp.com
rlcdn.com
agkn.com
addthis.com
sharethis.com
contextweb.com
bidswitch.net
sovrn.com
lijit.com
gumgum.com
sharethrough.com
nativo.com
triplelift.com
indexww.com
teads.tv
adyoulike.com
thetradedesk.com
tapad.com
drawbridge.com
lotame.com
crwdcntrl.net
adfox.ru
yandexadexchange.net
an.yandex.ru
pos.baidu.com
cpro.baidu.com
mintegral.com
yeahmobi.com
mobvista.com
appnext.com
startapp.com
start.io
airpush.com
leadbolt.com
revmob.com
nexage.com
adwhirl.com
adgrx.com
advertstream.com
simpli.fi
crosswise.net
undertone.com
lijit.com
adsymptotic.com
atemda.com
bidsystem.com
contextualyield.com
de17a.com
emxdgt.com
fidint.com
lkqd.com
sailthru.com
sail-horizon.com
stickyadstv.com
streamrail.com
smartclip.net
vidible.tv
jwpltx.com
anrdoezrs.net
avazutracking.net
clicksor.com
clickfuse.com
cpabuild.com
cpagrip.com
cpalead.com
cpatrend.com
df-srv.com
directrev.com
engagebdr.com
ero-advertising.com
exdynsrv.com
fast-adv.it
feedjit.com
fiksu.com
hitcpm.com
hilltopads.net
inpagepush.com
insticator.com
linkstorms.com
lockerdome.com
madadsmedia.com
mdadx.com
media-servers.net
megapu.sh
morgdm.ru
native-adv.com
newtentionads.com
onclicksuper.com
onclkds.com
padsdel.com
pagefair.com
piximedia.com
plugrush.com
pornado?no
""".replace("pornado?no\n", "")

TRACKERS = """google-analytics.com
googletagmanager.com
googletagservices.com
app-measurement.com
analytics.google.com
facebook.net
connect.facebook.net
analytics.facebook.com
graph-analytics.facebook.com
graph.facebook.com
pixel.facebook.com
mixpanel.com
api.mixpanel.com
amplitude.com
api.amplitude.com
segment.io
cdn.segment.com
api.segment.io
segment.com
appsflyer.com
t.appsflyer.com
events.appsflyer.com
adjust.com
app.adjust.com
kochava.com
control.kochava.com
branch.io
api2.branch.io
singular.net
tenjin.com
track.tenjin.com
tenjin.io
mparticle.com
tealiumiq.com
tags.tiqcdn.com
ensighten.com
nexus.ensighten.com
hotjar.com
static.hotjar.com
script.hotjar.com
fullstory.com
rs.fullstory.com
crazyegg.com
script.crazyegg.com
newrelic.com
bam.nr-data.net
js-agent.newrelic.com
app.bugsnag.com
notify.bugsnag.com
sessions.bugsnag.com
ingest.sentry.io
browser-intake-datadoghq.com
rum-http-intake.logs.datadoghq.com
cdn.logrocket.io
cdn.lr-ingest.io
logs-01.loggly.com
api.keen.io
heap.io
cdn.heapanalytics.com
heapanalytics.com
pendo.io
cdn.pendo.io
api-iam.intercom.io
nexus-websocket-a.intercom.io
widget.intercom.io
js.driftt.com
track.customer.io
static.klaviyo.com
scorecardresearch.com
sb.scorecardresearch.com
quantserve.com
pixel.quantserve.com
quantcount.com
imrworldwide.com
secure-us.imrworldwide.com
comscore.com
demdex.net
dpm.demdex.net
everesttech.net
cm.everesttech.net
omtrdc.net
2o7.net
hitbox.com
webtrekk.net
etracker.com
code.etracker.com
mc.yandex.ru
mc.yandex.com
onesignal.com
cdn.onesignal.com
pushwoosh.com
cdn.pushwoosh.com
appboy.com
dev.appboy.com
sdk.iad-01.braze.com
braze.com
clevertap.com
wzrkt.com
moengage.com
cdn.moengage.com
webengage.com
c.webengage.com
iterable.com
api.iterable.com
leanplum.com
localytics.com
profile.localytics.com
data.flurry.com
cloud.countly.com
api.count.ly
vortex.data.microsoft.com
vortex-win.data.microsoft.com
settings.data.microsoft.com
telemetry.microsoft.com
self.events.data.microsoft.com
watson.telemetry.microsoft.com
telecommand.telemetry.microsoft.com
activity.windows.com
data.mistat.xiaomi.com
tracking.miui.com
api.ad.xiaomi.com
samsungads.com
config.samsungads.com
log.tiktokv.com
mon.tiktokv.com
device-metrics-us.amazon.com
device-metrics-us-2.amazon.com
telemetry.revenuecat.com
api.revenuecat.com
js.hs-scripts.com
track.hubspot.com
munchkin.marketo.net
cdn.optimizely.com
logx.optimizely.com
dev.visualwebsiteoptimizer.com
cdn.mouseflow.com
cdn.luckyorange.com
cdn.inspectlet.com
t.contentsquare.net
in.getclicky.com
c.statcounter.com
static.woopra.com
trk.kissmetrics.com
apsalar.com
tune.com
mobileapptracking.com
trkn.us
apmebf.com
crashlytics.com
firebase-settings.crashlytics.com
app-measurement.com
liftoff.io
aarki.com
youappi.com
remerge.com
jampp.com
rtbhouse.com
adikteev.com
moloco.com
bigabid.com
persona.ly
smadex.com
kayzen.io
bidease.com
unicorn.com
unicornads.com
pubnative.com
verve.com
hyprmx.com
kidoz.com
superawesome.com
ogury.io
ogury.com
"""

THREATS = """# Formato: [prefijo:]dominio — prefijos validos: miner:, phishing:, malware:
# Sin prefijo = malware. Solo dominios documentados publicamente como maliciosos.
miner:coinhive.com
miner:coin-hive.com
miner:cryptoloot.pro
miner:crypto-loot.com
miner:minero.cc
miner:coinimp.com
miner:jsecoin.com
miner:webminepool.com
miner:webminerpool.com
miner:webmine.cz
miner:ppoi.org
miner:coin-have.com
miner:cryptonight?no
malware:vxvault.net
malware:malc0de.com
malware:malwaredomainlist.com
""".replace("miner:cryptonight?no\n", "")

def clean(block):
    out = []
    for line in block.strip().splitlines():
        line = line.strip().lower()
        if not line or line.startswith("#"):
            continue
        # quitar prefijo para validar el dominio, pero conservarlo en threats
        dom = line.split(":", 1)[1] if ":" in line and not line.startswith("http") else line
        if not re.match(r"^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$", dom):
            raise ValueError("dominio invalido: %r" % line)
        out.append(line)
    # dedup preservando orden
    seen, res = set(), []
    for d in out:
        if d not in seen:
            seen.add(d); res.append(d)
    return res

ads = clean(ADS); trk = clean(TRACKERS); thr = clean(THREATS)

def base(line):
    return line.split(":", 1)[1] if ":" in line else line

# solapes entre listas (prioridad: ads > trackers > threats)
b_ads, b_trk = set(map(base, ads)), set(map(base, trk))
overlap = (b_ads & b_trk) | (b_ads & set(map(base, thr))) | (b_trk & set(map(base, thr)))
if overlap:
    print("SOLAPE ENTRE LISTAS:", sorted(overlap))
    raise SystemExit(1)

os.makedirs(RAW, exist_ok=True)
open(RAW + "/ads.txt", "w").write("\n".join(ads) + "\n")
open(RAW + "/trackers.txt", "w").write("\n".join(trk) + "\n")
open(RAW + "/threats.txt", "w").write("\n".join(thr) + "\n")
print("ads=%d trackers=%d threats=%d total=%d" % (len(ads), len(trk), len(thr), len(ads)+len(trk)+len(thr)))
