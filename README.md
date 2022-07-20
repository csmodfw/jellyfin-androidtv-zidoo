
<h1 align="center">Jellyfin Android TV Zidoo-Edition</h1>  
<h3 align="center">Fork of the <a href="https://jellyfin.org">Jellyfin Project</a></h3>  
  
---  
  
<p align="center">  
<img alt="Logo banner" src="https://raw.githubusercontent.com/jellyfin/jellyfin-ux/master/branding/SVG/banner-logo-solid.svg?sanitize=true"/>  
<br/><br/>  
<a href="https://github.com/jellyfin/jellyfin-androidtv">  
<img alt="GPL 2.0 License" src="https://img.shields.io/github/license/jellyfin/jellyfin-androidtv.svg"/>  
</a>  
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
  
"Zidoo-Edition" is a unofficial community fork of the Jellyfin Android TV client, adapted to better run on [Zidoo media players](https://www.zidoo.tv) running Android 9+.
### Release [downloads here](https://github.com/Andy2244/jellyfin-androidtv-zidoo/releases)
TIP: you can directly install from your browser via `http://zidoo_ip:18888` web interface
### App ONLY works with latest 10.8.1 server!
- feature roadmap
    - ~add smart "original language" audio selection logic based on tmdb id's~ _done_
    - ~add better transcoding support and fallback's for unsupported codecs~ _done_
    - ~add more audio-only transcoding options (DD only, PCM 5.1 -> DD5.1)~ _done_
    - seekable transcode playback
    - add atmos, dv logos
    - ~improve layouts~ _done_
    - small useability improvements _(smart screen views)_
      - focus selection border
      - add missing vertical-grid features _(genre)_
    - working support for trailer/intro plugin's
    - ~use new Android API from latest beta FW~ _done_
    - script hotkey action's _(xml kodi compatible)_
    - fix all NFS mount cases
### Added Features/Fixes:
 - Playback integration with the internal Zidoo player
   - working resume, seek, watched states and server playback reporting 
   - http streaming support
   - smb, nfs support via `Direct Path` option
   - "smart" audio/subtitle selection logic
   - working tarnscoding for unsupported formats/codec's
 - UI layout fixes
	 - cutoff grids, ui scaling fixed
### How to use with "Direct Path" option (smb/nfs):
- setup your Jellyfin server [library's](https://jellyfin.org/docs/general/server/libraries.html) with network paths or path substitution via `Shared network folder:` option
	- Formats
		- `smb://smb_user:smb_password@server_ip/share/folder`
		- `smb://smb_user@server_ip/share/folder`
		- `nfs://server_ip/nfs_export/:`
		- `nfs://server_ip/nfs_export/:/tv`
			- nfs needs the `/:` special marker right after the actual nfs export name/path portion!
	- Examples: 
		- `smb://andy:123456@192.168.1.101/htpc-share/series`
		- `smb://andy@192.168.1.101/htpc-share/series`
		- `nfs://192.168.1.101/mnt/media/movies/:` -> with nfs export `/mnt/media/movies`
 		- `nfs://192.168.1.101/mnt/media/:/movies` -> with nfs export `/mnt/media`
![setting](https://user-images.githubusercontent.com/5340247/174437861-c1db621a-d4b2-4696-b33c-5152c0c67fb6.png)
- enable the `Direct Path` option in the JellyfinTv client
### Community
- **for major bugs/issues regarding the Zidoo-Edition, please open a github issue**
- for questions, suggestions or help use the [Zidoo forum](http://forum.zidoo.tv/index.php)
	- [Support Post](http://forum.zidoo.tv/index.php?threads/jellyfintv-zidoo-edition-support-post.93902/) for JellyfinTv Zidoo-Edition
- german Community [Zidoo forum](https://www.android-mediaplayer.de/forum/index.php?board/82-zidoo-player-x6-pro-x8-x9s-z9s-z9x-x10-z10-z10pro-x20-x20pro-z1000-z1000pro-uhd2/)
- AVS forum [Zidoo Post](https://www.avsforum.com/threads/zidoo-z9x-rtd1619-thread.3140924/page-999)
- Zidoo Community software site www.mcbluna.net
