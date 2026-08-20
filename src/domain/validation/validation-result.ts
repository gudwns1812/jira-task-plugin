export interface ValidationMessage {
  path: string;
  message: string;
}

export interface ValidationResult {
  errors: ValidationMessage[];
  warnings: ValidationMessage[];
  derived: {
    labels: string[];
  };
}
