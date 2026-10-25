# Rupert Tablet — v0.11

La Galaxy Tab A9 es la casa de Rupert: enchufada, siempre encendida, con su cara en pantalla.
Es el "compañero nativo" que el Cloud Core v0.10 dejó pendiente: escucha permanente con wake word,
algo que la PWA no puede hacer en segundo plano.

```text
Tablet (app Kotlin, android/)
  ├─ wake word local (openWakeWord, sin red)
  ├─ graba hasta que dejas de hablar
  │      │ Supabase Auth JWT
  │      ▼
  │   rupert-transcribe ──→ Whisper (Cloudflare) · idioma automático
  │   rupert ─────────────→ Gemini Flash (free tier) · respaldo Cloudflare
  │                         memoria y conversación en Supabase (RLS)
  └─ responde con el TTS de Android, en el idioma en que le hablaste
```

Costo: $0. Gemini, Whisper en Cloudflare y Supabase se usan en sus planes gratis.

## La cara

Diseño de Rupert (hoja de personaje generada por Argos/ChatGPT para Mario): `docs/design/rupert-hoja-personaje.png`.
La app usa cada viñeta tal cual como un estado:

| Estado | Viñeta |
|---|---|
| Dormido / esperando | dormido en la almohada |
| Escuchando | acomodándose los lentes |
| Pensando | mano en la barbilla |
| Hablando | manos abiertas |
| Emocionado | puños arriba |
| Error | ceja levantada |

## Personalidad

Vive en `supabase/functions/rupert/index.ts` (`PERSONA`): entusiasta, curioso, habla rápido,
cálido y honesto, con energía de viajero excéntrico. Siempre es Rupert; no cita series ni dice ser otro personaje.
Responde en español de México y cambia de idioma cuando tú cambias.

## Puesta en marcha (todo desde el celular o la tablet)

### 1. Llave gratis de Gemini
1. Entra a <https://aistudio.google.com/apikey> y crea una API key (plan gratis, sin tarjeta).
2. En Supabase → Project Settings → Edge Functions → Secrets agrega:
   - `GEMINI_API_KEY` = tu llave
   - (opcional) `GEMINI_MODEL` = `gemini-flash-latest` (valor por defecto)

No mandes notas de clientes a Rupert: el free tier de Gemini puede usar los datos para mejorar sus productos.

### 2. Desplegar las funciones sin laptop
En GitHub → Settings → Secrets and variables → Actions agrega:
- `SUPABASE_ACCESS_TOKEN` (de <https://supabase.com/dashboard/account/tokens>)
- `SUPABASE_PROJECT_REF` (el `xxxx` de `https://xxxx.supabase.co`)

Después, en Actions → **supabase-deploy** → Run workflow. También corre solo cuando cambian las funciones en `main`.

### 3. Latido anti-pausa
El plan gratis de Supabase pausa el proyecto tras una semana sin uso. Dos latidos lo evitan:
- la app de la tablet hace una lectura mínima cada 24 h;
- el workflow **supabase-heartbeat** hace otra cada 3 días (necesita los secretos `SUPABASE_URL` y `SUPABASE_ANON_KEY`).

GitHub apaga los workflows programados si el repo pasa 60 días sin actividad; el latido de la tablet cubre ese caso.

### 4. Instalar la app
1. En la tablet abre <https://github.com/Klymapo/rupert/releases/tag/tablet-latest> y descarga `rupert-tablet.apk`.
2. Permite "Instalar apps desconocidas" para el navegador y abre el APK.
3. Cada build nuevo se instala encima del anterior (misma firma).

### 5. Configurar
En la app → **Ajustes**:
1. URL del proyecto Supabase y llave publishable/anon (nunca la service_role).
2. Inicia sesión con tu cuenta de Supabase Auth:
   - con contraseña: correo + contraseña → "Guardar e iniciar sesión"; o
   - con código (el login por correo de la PWA): "Enviar código", escribe el código de 6 dígitos y "Guardar e iniciar sesión".
     Para que el correo traiga el código, en Supabase → Authentication → Email Templates → Magic Link agrega `{{ .Token }}` al texto.
3. "Quitar optimización de batería".
4. En Ajustes de Android → Batería → Límites de uso en segundo plano → **Aplicaciones que nunca entran en suspensión** → agrega Rupert.
5. Para voces más naturales: Ajustes → Administración general → Salida de texto a voz → descarga los idiomas que uses.

### 6. El wake word "Rupert"
Hasta que exista el modelo propio, la app despierta con **"Hey Jarvis"** (modelo de prueba de openWakeWord)
o tocando la cara de Rupert.

Para entrenar "Rupert" (gratis, en Google Colab):
1. Abre el notebook `automatic_model_training.ipynb` del repo `dscripka/openWakeWord`.
2. Palabra objetivo: `rupert` (prueba también `rupert!` y `ruperto` si falla con el acento).
3. El notebook genera `rupert.tflite`. Pásalo a la tablet.
4. En la app → Ajustes → **Importar rupert.tflite**. Rupert lo carga sin reiniciar.
5. Si despierta de más, sube la sensibilidad; si no te oye, bájala.

## Cómo platicar
- Di la palabra (o toca la cara), espera el "¿Sí?" y habla.
- Después de responder, Rupert sigue escuchando unos segundos: puedes seguir sin repetir su nombre.
- Habla en otro idioma y te contesta en ese idioma.
- "Eso es todo", "gracias Rupert" o "adiós" cierran la plática.
- Los comandos `buscar …`, `leer …` y `añadir … :: …` siguen funcionando por voz.

## Límites conocidos
- Android no deja iniciar el micrófono desde segundo plano: después de reiniciar la tablet hay que abrir la app una vez.
- La detección de fin de frase es por volumen; en lugares muy ruidosos puede cortar antes o tardar más.
- El APK se firma con una llave de sideload que está en el repo (`android/app/rupert-sideload.keystore`). Sirve para instalar a mano, no para Play Store.
