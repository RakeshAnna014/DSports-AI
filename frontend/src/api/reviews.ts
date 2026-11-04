import apiClient from './client';

export interface ReviewResult {
  id: string;
  productId: string;
  orderId: string;
  rating: number;
  title: string;
  comment: string | null;
  verifiedPurchase: boolean;
  helpfulCount: number;
  status: string;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface RatingSummaryResult {
  productId: string;
  averageRating: number;
  totalReviews: number;
  distribution: Record<number, number>;
  rating1Count: number;
  rating2Count: number;
  rating3Count: number;
  rating4Count: number;
  rating5Count: number;
}

export interface CreateReviewRequest {
  productId: string;
  orderId: string;
  rating: number;
  title: string;
  comment?: string;
}

export interface UpdateReviewRequest {
  rating: number;
  title: string;
  comment?: string;
}

export interface ReviewEligibility {
  eligible: boolean;
  reason: 'ELIGIBLE' | 'ALREADY_REVIEWED' | 'NOT_PURCHASED_OR_NOT_DELIVERED';
  orderId: string | null;
  existingReviewStatus: string | null;
}

export interface ModerateReviewRequest {
  approve: boolean;
}

export const reviewsApi = {
  create: (data: CreateReviewRequest) =>
    apiClient.post<ReviewResult>('/v1/reviews', data).then((r) => r.data),

  update: (id: string, data: UpdateReviewRequest) =>
    apiClient.put<ReviewResult>(`/v1/reviews/${id}`, data).then((r) => r.data),

  getById: (id: string) =>
    apiClient.get<ReviewResult>(`/v1/reviews/${id}`).then((r) => r.data),

  getProductReviews: (productId: string, page = 0, size = 20) =>
    apiClient
      .get<ReviewResult[]>(`/v1/reviews/product/${productId}`, { params: { page, size } })
      .then((r) => r.data),

  getRatingSummary: (productId: string) =>
    apiClient
      .get<RatingSummaryResult>(`/v1/reviews/product/${productId}/summary`)
      .then((r) => r.data),

  getEligibility: (productId: string) =>
    apiClient.get<ReviewEligibility>('/v1/reviews/eligibility', { params: { productId } }).then((r) => r.data),

  moderate: (id: string, data: ModerateReviewRequest) =>
    apiClient.post<ReviewResult>(`/v1/reviews/admin/${id}/moderate`, data).then((r) => r.data),

  voteHelpful: (id: string) =>
    apiClient.post<ReviewResult>(`/v1/reviews/${id}/helpful`).then((r) => r.data),
};
