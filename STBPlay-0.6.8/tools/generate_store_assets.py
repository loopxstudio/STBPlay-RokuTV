from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import math,json
root=Path(__file__).resolve().parents[1]
a=root/'publishing/store-listing/assets'
font='/System/Library/Fonts/Supplemental/Arial.ttf'
bold='/System/Library/Fonts/Supplemental/Arial Bold.ttf'
def F(n,b=False):return ImageFont.truetype(bold if b else font,n)
def background(w,h):
 im=Image.new('RGB',(w,h)); px=im.load()
 for y in range(h):
  for x in range(w):
   t=max(0,1-math.hypot((x-w*.78)/(w*.8),(y-h*.45)/(h*1.2)))
   px[x,y]=(int(7+8*t),int(15+24*t),int(27+38*t))
 return im
blue='#18a7ff';muted='#a9bfd6';white='#f7fbff'
def name(d,x,y,size=60):
 d.text((x,y),'STB',font=F(size,True),fill=white)
 width=d.textlength('STB',font=F(size,True))
 d.text((x+width,y),'Play',font=F(size,True),fill=blue)
def tv(d,box):
 x,y,x2,y2=box;d.rounded_rectangle(box,28,fill='#102237',outline='#2d526c',width=2)
 d.rounded_rectangle((x+14,y+14,x2-14,y2-26),18,fill='#08121f',outline='#147fbd',width=2)
 cx=(x+x2)//2; cy=(y+y2)//2-6
 d.polygon([(cx-22,cy-38),(cx-22,cy+38),(cx+40,cy)],fill=blue)
 d.line((cx,y2,cx,y2+20),fill='#4b6981',width=7);d.line((cx-48,y2+20,cx+48,y2+20),fill='#4b6981',width=5)
# Mechanical, dimension-only export of the existing app artwork.
im=Image.open(root/'app/src/main/res/drawable-nodpi/stbplay_logo.png').convert('RGB')
im.resize((512,512),Image.Resampling.LANCZOS).convert('RGBA').save(a/'app-icon-512.png')
# Three editable-layout alternatives. No program art, broadcaster marks or device mockups.
for index in range(1,4):
 im=background(1024,500);d=ImageDraw.Draw(im)
 d.line((64,60,112,60),fill=blue,width=4)
 name(d,64,86,66)
 if index==1:
  d.text((64,186),'Your TV,',font=F(49,True),fill=white)
  d.text((64,243),'your way.',font=F(49,True),fill=white)
  d.text((64,334),'Bring your own playlists',font=F(24),fill=muted)
  tv(d,(624,118,938,338))
 elif index==2:
  d.text((64,188),'One place for',font=F(43,True),fill=white)
  d.text((64,240),'your playlists.',font=F(43,True),fill=white)
  d.text((64,329),'Stalker  /  Xtream  /  M3U',font=F(23),fill=muted)
  for i,t in enumerate(['Live TV','Movies','Series']):
   y=105+i*98
   d.rounded_rectangle((650,y,944,y+76),16,fill='#11273c',outline='#294c66',width=2)
   d.rounded_rectangle((667,y+15,711,y+59),10,fill='#0b1928',outline='#168ecc',width=2)
   d.polygon([(683,y+26),(683,y+48),(699,y+37)],fill=blue)
   d.text((729,y+24),t,font=F(23,True),fill=white)
 else:
  d.text((64,188),'Made for',font=F(49,True),fill=white)
  d.text((64,245),'the remote.',font=F(49,True),fill=white)
  d.text((64,334),'Browse. Select. Watch.',font=F(24),fill=muted)
  d.rounded_rectangle((708,76,874,411),56,fill='#10253b',outline='#3b617f',width=3)
  d.ellipse((728,143,854,269),fill='#0a1828',outline=blue,width=3)
  d.ellipse((767,182,815,230),fill='#159fe9');d.text((778,196),'OK',font=F(16,True),fill=white)
  for x,y,pts in [(791,158,[(785,164),(797,164),(791,155)]),(791,254,[(785,249),(797,249),(791,258)]),(742,206,[(748,200),(748,212),(739,206)]),(840,206,[(834,200),(834,212),(843,206)])]:d.polygon(pts,fill=muted)
  d.ellipse((756,310,777,331),fill='#294c66');d.ellipse((808,310,829,331),fill='#294c66')
 d.text((64,430),'ANDROID TV  /  GOOGLE TV',font=F(15,True),fill='#7899b4')
 im.save(a/f'feature-graphics/feature-{index:02d}-1024x500.png')
# TV store banner and installed-TV launcher banner share the same original layout.
im=background(1280,720);d=ImageDraw.Draw(im)
d.rounded_rectangle((512,110,768,335),40,fill='#0b192b',outline='#315a79',width=3)
d.polygon([(597,157),(597,290),(710,223)],fill=blue)
name(d,393,388,112)
d.text((420,541),'YOUR TV, YOUR WAY',font=F(38),fill=muted)
im.save(a/'tv-banner-1280x720.png')
im.resize((320,180),Image.Resampling.LANCZOS).save(a/'tv-launcher-banner-320x180.png')
im.resize((320,180),Image.Resampling.LANCZOS).save(root/'app/src/main/res/drawable-nodpi/stbplay_tv_banner_art.png')
(root/'app/src/main/res/drawable/stbplay_tv_banner.xml').write_text('''<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:width="160dp" android:height="90dp">
        <bitmap android:src="@drawable/stbplay_tv_banner_art" android:gravity="fill" android:filter="true" />
    </item>
</layer-list>
''')
# Retain a compact design specification and generator reference for repeatability.
(a/'source/design-spec.json').write_text(json.dumps({'palette':{'background':'#070f1b','accent':blue,'text':white,'secondary':muted},'font':'Arial / Arial Bold','featureDimensions':[1024,500],'tvStoreBannerDimensions':[1280,720],'tvLauncherDimensions':[320,180],'icon':'Existing STBPlay artwork, scaled only','screenshotPolicy':'Unaltered captures from the current app using synthetic demo data; no provider credentials or copyrighted program images.'},indent=2))
print('Exported icon, three feature graphics and both TV banners.')
