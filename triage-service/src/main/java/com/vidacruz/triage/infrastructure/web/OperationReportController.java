package com.vidacruz.triage.infrastructure.web;

import com.vidacruz.triage.application.GetOperationReport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/triage/reportes")
class OperationReportController {

	private final GetOperationReport getOperationReport;

	OperationReportController(GetOperationReport getOperationReport) {
		this.getOperationReport = getOperationReport;
	}

	/**
	 * RF-05: hourly operation report over {@code [desde, hasta)}. Parameters are taken as text so that a missing or
	 * malformed one is reported by name, together with the others, in the usual 400 format.
	 */
	@GetMapping("/operacion")
	Mono<OperationReportResponse> operation(
			@RequestParam(name = OperationReportParameters.FROM, required = false) String desde,
			@RequestParam(name = OperationReportParameters.TO, required = false) String hasta,
			@RequestParam(name = OperationReportParameters.ZONE, required = false) String zonaHoraria) {
		return Mono.fromSupplier(() -> OperationReportParameters.parse(desde, hasta, zonaHoraria))
			.flatMap(params -> getOperationReport.report(params.from(), params.to(), params.zone())
				.map(hours -> OperationReportResponse.from(params, hours)));
	}

}
