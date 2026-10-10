# ItemBridge distribution customization

ItemLoom embeds the MIT-licensed `cn.gtemc:itembridge:1.0.32` artifact from
https://repo.gtemc.net/releases/. The reviewed upstream source revision is
`bdf107863b64dc6d70888e4597a7413d3eea88fb` of https://github.com/jhqwqmc/ItemBridge.
The input JAR SHA-256 is
`07276894b33aa8ee7c3b9d2969295b40668aafcbaa75d3049de1bc659c99054e`.

The build removes the NI/SX provider classes, their nested classes, and upstream
`HookHelper` classes. Removing only the providers would leave eagerly linked
factory references in upstream discovery. The source in this directory supplies
discovery for the remaining providers, loading their checks only for an installed,
enabled plugin. ItemBridge's core, context types, and other provider implementations
remain from the original artifact. Everything is relocated into ItemLoom's private
namespace during packaging.

This is an explicit customization of an included third-party library, not NI/SX
plugin code or an attempt to hide third-party provenance. The discovery contract,
provider names, and package-private factories come from ItemBridge. Keep its MIT
license and this modification record. No Kotlin, NI/SX plugin implementation,
or external provider plugin is bundled.

The artifact audit rejects any remaining NI/SX adapter class or direct JVM type
reference. Updating ItemBridge requires reviewing the pinned input, available
providers, customization, and licenses together.
