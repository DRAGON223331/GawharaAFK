# GawharaAFK

بيكتب `[AFK]` فوق راس اللاعب وفي التاب لما يبقى خامل.

## التركيب
1. حط `GawharaAFK.jar` في فولدر `plugins/`
2. اعمل Restart
3. عدّل `plugins/GawharaAFK/config.yml` واكتب `/afk reload`

## الأوامر والصلاحيات
- `/afk` تبديل AFK يدوي (afk.use - للكل)
- `/afk reload` (afk.reload - للأدمن)
- `afk.exempt` اللاعب مش بيدخل AFK تلقائي ومش بيتطرد

## البناء
JDK 25 + Maven:  `mvn clean package`  →  `target/GawharaAFK.jar`
أو ارفع الفولدر على GitHub وهيتبني تلقائي من تبويب Actions.
