package compile.resolution;

import util.Result;
import util.exceptions.ResolutionError;

import java.util.List;

public interface Resolver {
    Result<Boolean, ResolutionError> resolve(List<String> inputs);


}
