# ============================================
# 1-BOSQICH: BUILD (kod compile qilinadi)
# ============================================
FROM maven:3.9-eclipse-temurin-21 AS build
# ^ Bu bosqichga "build" nomi berildi, keyinroq undan foydalanamiz

WORKDIR /app
# ^ Konteyner ichida ishlaydigan papka (hammasi shu yerga tushadi)

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
# ^ Avval FAQAT dependency fayllarni ko'chiramiz (kod emas!)
#   Sabab: agar keyinroq faqat kod o'zgarsa, Docker bu qatorlarni
#   qaytadan bajarmaydi (cache'dan foydalanadi) — build tezlashadi

RUN chmod +x mvnw
#   mvnw faylida execute (ishga tushirish) huquqini berish

RUN ./mvnw dependency:go-offline -B
# ^ pom.xml asosida barcha kutubxonalarni yuklab, cache qiladi
#   (internetdan hali kod yozilmasdan oldin yuklaydi)

COPY src src
# ^ Endi asosiy kodni ko'chiramiz (bu eng ko'p o'zgaradigan qism,
#   shuning uchun oxirida qo'ydik)

RUN ./mvnw package -DskipTests
# ^ Loyihani compile qilib, fat JAR yaratadi (target/*.jar)
#   -DskipTests — testlarni o'tkazib yuboradi (build tezroq bo'lishi uchun)

RUN mkdir -p target/dependency && (cd target/dependency; jar -xf ../*.jar)
# ^ Yaratilgan JAR'ni ochib (unzip), ichidagi qismlarni ajratadi:
#   - BOOT-INF/lib      (kutubxonalar)
#   - BOOT-INF/classes  (sizning compiled kodingiz)
#   - META-INF          (meta-ma'lumot)


# ============================================
# 2-BOSQICH: RUNTIME (faqat ishga tushirish)
# ============================================
FROM eclipse-temurin:21-jre
# ^ Yangi, TOZA image boshlanadi. Build bosqichidagi Maven,
#   source kod, keraksiz fayllar bu yerga tushmaydi —
#   natijada final image kichikroq va xavfsizroq bo'ladi

RUN groupadd -r spring && useradd -r -g spring spring
# ^ Root bo'lmagan "spring" foydalanuvchi yaratiladi
#   (xavfsizlik: agar konteyner buzilsa, root huquqi bo'lmaydi)

USER spring:spring
# ^ Keyingi barcha buyruqlar shu user nomidan bajariladi

ARG DEPENDENCY=/app/target/dependency
# ^ Build bosqichida yaratilgan papkaga yo'l (o'zgaruvchi sifatida)

COPY --from=build ${DEPENDENCY}/BOOT-INF/lib /app/lib
# ^ Kutubxonalarni build bosqichidan runtime image'ga ko'chiradi
#   (--from=build => 1-bosqichdan olyapmiz)
#   Bu LAYER kam o'zgaradi (dependency'lar kamdan-kam yangilanadi)

COPY --from=build ${DEPENDENCY}/META-INF /app/META-INF
# ^ Meta-ma'lumotni ko'chiradi (kichik, deyarli o'zgarmaydi)

COPY --from=build ${DEPENDENCY}/BOOT-INF/classes /app
# ^ Sizning compiled .class fayllaringiz — ENG TEZ o'zgaradigan qism,
#   shuning uchun ENG OXIRIDA joylashtirildi.
#   Cache mantiqi: faqat shu qatorgacha bo'lgan narsa o'zgarganda
#   qolgan layer'lar qayta build bo'ladi, aks holda cache'dan olinadi

ENTRYPOINT ["java","-cp","app:app/lib/*","com.javier.telegrambot.TelegramBotApplication"]
# ^ Konteyner ishga tushganda bajariladigan buyruq:
#   -cp "app:app/lib/*" — classpath: /app (sizning class'laringiz)
#                          + /app/lib ichidagi barcha JAR'lar
#   hello.Application    — main() metodi bor klass
#   (O'z paket/klass nomingizga moslang!)