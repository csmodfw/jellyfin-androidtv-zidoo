
<h1 align="center">Jellyfin Android TV Zidoo-Edition</h1>  
<h3 align="center">Fork of the <a href="https://jellyfin.org">Jellyfin Project</a></h3>  
  
---  
  
<p align="center">  
<img alt="Logo banner" src="https://raw.githubusercontent.com/jellyfin/jellyfin-ux/master/branding/SVG/banner-logo-solid.svg?sanitize=true"/>  
<br/><br/>  
<a href="https://github.com/jellyfin/jellyfin-androidtv">  
<img alt="GPL 2.0 License" src="https://img.shields.io/github/license/jellyfin/jellyfin-androidtv.svg"/>  
</a>  
<a href="https://github.com/Andy2244/jellyfin-androidtv-zidoo/releases">  
<img alt="Current Zidoo Edition Release" src="https://img.shields.io/github/release/jellyfin/jellyfin-androidtv.svg"/>  
</a>  
<a href="https://translate.jellyfin.org/projects/jellyfin-android/jellyfin-androidtv/">  
<img alt="Translation Status" src="https://translate.jellyfin.org/widgets/jellyfin-android/-/jellyfin-androidtv/svg-badge.svg"/>  
</a>  
<br/>  
<a href="https://opencollective.com/jellyfin">  
<img alt="Donate" src="https://img.shields.io/opencollective/all/jellyfin.svg?label=backers"/>  
</a>  
<a href="https://features.jellyfin.org">  
<img alt="Feature Requests" src="https://img.shields.io/badge/fider-vote%20on%20features-success.svg"/>  
</a>  
<a href="https://matrix.to/#/+jellyfin:matrix.org">  
<img alt="Chat on Matrix" src="https://img.shields.io/matrix/jellyfin:matrix.org.svg?logo=matrix"/>  
</a>  
<a href="https://www.reddit.com/r/jellyfin">  
<img alt="Join our Subreddit" src="https://img.shields.io/badge/reddit-r%2Fjellyfin-%23FF5700.svg"/>  
</a>  
  
Jellyfin Android TV Zidoo-Edition is a Jellyfin client adapted to better run on [Zidoo media players](https://www.zidoo.tv) running Android 9+.  
### Release [downloads here](https://github.com/Andy2244/jellyfin-androidtv-zidoo/releases)
- beta1 phase:
	- initial release to get all basic functionality working
- future plans
	- add/improve default player audio/subtitle handling
	- add support for vlclib software decode fallback on Hi10 *(H264/10 bit)* files
	- if the upcoming Plex client is released, maybe we get better working http stream support
		- atm playback via http stream is buggy/glitchy so its disabled via Zidoo player
  
### Added Features/Fixes:
 - Playback integration with the internal Zidoo player
   - working resume, seek and watched handling
   - smb, nfs support via `Direct Path` option
 - UI layout fixes
	 - cutoff grids, ui scaling fixed
### How to use:
- setup your Jellyfin server [library's](https://jellyfin.org/docs/general/server/libraries.html) with network paths or path substitution via `Shared network folder:` option
	- Formats
		- smb://smb_user:smb_password@server_ip/share/folder
		-  nfs://server_ip/folder
	- Examples: 
		- smb://andy:1234@192.168.1.101/htpc-share/series
![setting](https://user-images.githubusercontent.com/5340247/174437861-c1db621a-d4b2-4696-b33c-5152c0c67fb6.png)
- enable the `Direct Path` and `Use Zidoo player` option in the JellyfinTv client
### Community
- **for major bugs/issues regarding the Zidoo-Edition, please open a github issue**
- for questions, suggestions or help use the [Zidoo forum](http://forum.zidoo.tv/index.php)
	- [Support Post](http://forum.zidoo.tv/index.php?threads/jellyfintv-zidoo-edition-support-post.93902/) for JellyfinTv Zidoo-Edition
- german Community [Zidoo forum](https://www.android-mediaplayer.de/forum/index.php?board/82-zidoo-player-x6-pro-x8-x9s-z9s-z9x-x10-z10-z10pro-x20-x20pro-z1000-z1000pro-uhd2/)
- AVS forum [Zidoo Post](https://www.avsforum.com/threads/zidoo-z9x-rtd1619-thread.3140924/page-999)
- Zidoo Community software site www.mcbluna.net
