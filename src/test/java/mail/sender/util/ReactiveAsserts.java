package mail.sender.util;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

public class ReactiveAsserts {

    public static void assertEmptyMono(Mono<?> mono) {
        StepVerifier.create(mono).verifyComplete();
    }

    public static <T> void assertMonoElement(Mono<T> mono, T element) {
        StepVerifier.create(mono).expectNext(element).verifyComplete();
    }

    public static void assertMonoError(Mono<?> mono, Class<? extends RuntimeException> errorClass) {
        StepVerifier.create(mono).verifyError(errorClass);
    }
}
