# Rupert · auditoría de mantenimiento (2026-10-12)

**Dictamen:** conservar como aplicación separada. Fuente pública, conversaciones y memoria privada fuera de Git.

## Hallazgos

- El inventario contenía 58 archivos rastreados: PWA en `web/`, servicio Supabase Edge Functions, SQL con RLS y pruebas Python. No hay binarios pesados relevantes que limpiar.
- El frontend `web/app.js` usa el CDN de `@supabase/supabase-js` y por tanto **necesita red para ese módulo en una carga inicial sin caché**. No prometer uso completamente offline.
- `sendMessage` no garantizaba reactivar el botón Enviar ante una excepción de red de `supabase.functions.invoke`; se añadió `try/catch/finally` y aviso textual seguro.
- El archivo `docs/SECURITY.md` recomendaba hacer privado el repositorio, mientras `main` era público. Se corrigió la política: código público, claves administrativas/estado privado fuera de Git, publicación del cliente solo con llave anon/publishable.
- La CI comprueba Python y ahora sintaxis JS; tiene permisos de contenido solo lectura, límite de tiempo y cancelación de validaciones anteriores en la misma rama para ahorrar minutos.
- La API de GitHub informa `has_pages=false` al corte de auditoría aunque hay workflow de Pages. **Un YAML de despliegue no prueba que el portal ya esté publicado.** Revisar Settings → Pages antes de anunciar URL.

**No realizado:** inspección de secretos almacenados en servicios externos, pentest de RLS, pruebas reales de voz, confirmación de Cloudflare/Supabase activo o validación de uso gratuito ilimitado.

**Seguimiento:** comprobar autenticación/memory RLS con cuenta de pruebas y evitar introducir tokens en la interfaz. No añadir ejecuciones periódicas ni servicios de pago sin autorización.
