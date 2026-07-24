# Phone Grouping TTS

Tizim TTS Engine sifatida ishlaydigan proksi. Matnni RHVoice'ga yuborishdan oldin
telefon raqamlarini guruhlarga ajratadi (masalan `+998949835707` -> `+998 94 983 57 07`),
so'ngra haqiqiy talaffuzni RHVoice orqali amalga oshiradi.

**Talab:** RHVoice ilovasi (`com.github.olga_yakovleva.rhvoice.android`) qurilmada
o'rnatilgan bo'lishi shart — bu ilova undan foydalanadi, o'zi ovoz sintez qilmaydi.

## Push va build qilish

```
git init
git add .
git commit -m "initial"
git remote add origin <repo-url>
git push -u origin main
```

Push qilingandan so'ng GitHub Actions avtomatik ishga tushadi va APK'ni
"Actions" bo'limidagi tugallangan workflow'ning "Artifacts" qismida
`app-debug.apk` nomi bilan qoldiradi.

## O'rnatish

1. APK'ni yuklab, telefonga o'rnat.
2. Sozlamalar > Til va kiritish > Matnni nutqqa aylantirish > Afzal ko'rilgan
   dvigatel — "Phone Grouping TTS" ni tanla.

## Guruhlash qoidasini o'zgartirish

`app/src/main/java/uz/safar/ttsproxy/PhoneGroupingTtsService.kt` faylidagi
`groupPhoneNumbers()` funksiyasi ichida regex qoidalarini tahrirlang.
