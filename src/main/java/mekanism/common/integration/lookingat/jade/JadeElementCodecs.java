package mekanism.common.integration.lookingat.jade;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import java.util.function.Function;
import mekanism.common.integration.lookingat.ChemicalElement;
import mekanism.common.integration.lookingat.EnergyElement;
import mekanism.common.integration.lookingat.FluidElement;
import mekanism.common.integration.lookingat.ILookingAtElement;
import mekanism.common.integration.lookingat.TextElement;
import net.neoforged.neoforge.common.util.NeoForgeExtraCodecs;

final class JadeElementCodecs {

    private static final MapCodec<ILookingAtElement> FLUID_OR_CHEMICAL_CODEC = alternativeElement(
          FluidElement.CODEC,
          ChemicalElement.CODEC,
          (ILookingAtElement element) -> switch (element) {
              case FluidElement fluidElement -> DataResult.success(Either.left(fluidElement));
              case ChemicalElement chemicalElement -> DataResult.success(Either.right(chemicalElement));
              default -> DataResult.error(() -> "Unknown Element Type, expected either fluid or chemical");
          }
    );
    private static final MapCodec<ILookingAtElement> ENERGY_OR_TEXT_CODEC = alternativeElement(
          EnergyElement.CODEC,
          TextElement.CODEC,
          (ILookingAtElement element) -> switch (element) {
              case EnergyElement energyElement -> DataResult.success(Either.left(energyElement));
              case TextElement textElement -> DataResult.success(Either.right(textElement));
              default -> DataResult.error(() -> "Unknown Element Type, expected either energy or text");
          }
    );
    static final Codec<ILookingAtElement> ELEMENT_CODEC = NeoForgeExtraCodecs.withAlternative(FLUID_OR_CHEMICAL_CODEC, ENERGY_OR_TEXT_CODEC).codec();

    private JadeElementCodecs() {
    }

    private static <B, L extends B, R extends B> MapCodec<B> alternativeElement(MapCodec<L> leftBase, MapCodec<R> rightBase,
          final Function<? super B, ? extends DataResult<? extends Either<L, R>>> from) {
        MapCodec<Either<L, R>> base = Codec.mapEither(leftBase, rightBase);
        return Codec.of(base.flatComap(from), base.map(Either::unwrap), () -> base + "[flatComapMapped]");
    }
}
