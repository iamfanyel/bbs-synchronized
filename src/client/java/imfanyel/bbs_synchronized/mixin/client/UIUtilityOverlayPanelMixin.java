package imfanyel.bbs_synchronized.mixin.client;

import imfanyel.bbs_synchronized.client.ClientModelSync;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.utility.UIUtilityOverlayPanel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import net.minecraft.client.resource.language.I18n;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a "Server Sync" section to the utility overlay panel (F6) with
 * buttons that trigger the sync commands, so players don't have to type them.
 * The buttons go through the exact same server commands as typing them would,
 * keeping permissions and feedback identical.
 */
@Mixin(value = UIUtilityOverlayPanel.class, remap = false)
public abstract class UIUtilityOverlayPanelMixin
{
    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void bbsSynchronized$addSyncSection(IKey title, Runnable callback, CallbackInfo ci)
    {
        UIUtilityOverlayPanel self = (UIUtilityOverlayPanel) (Object) this;

        /* Labels come from the standard lang files (assets/bbs_synchronized/
         * lang/*.json); the panel is rebuilt every time it opens, so language
         * switches are picked up naturally. The buttons bypass the chat
         * command — feedback arrives as dashboard notifications instead */
        UIButton reload = new UIButton(IKey.raw(I18n.translate("bbs_synchronized.ui.download")), (b) ->
        {
            self.close();
            ClientModelSync.requestFullSync();
        });
        UIButton upload = new UIButton(IKey.raw(I18n.translate("bbs_synchronized.ui.upload")), (b) ->
        {
            self.close();
            ClientModelSync.requestUploadNew();
        });

        self.view.add(
            UI.label(IKey.raw(I18n.translate("bbs_synchronized.ui.section"))).marginTop(UIConstants.SECTION_GAP),
            UI.row(reload, upload)
        );
    }

}
